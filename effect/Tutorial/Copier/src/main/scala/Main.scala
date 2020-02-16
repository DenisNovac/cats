import cats.effect.{IO, Resource}
import cats.implicits._
import java.io._

import javax.lang.model.util.Elements.Origin

object Copier {
  /**
   * В функциональном программировании функция копирования из одного места в другое не должна ничего копировать сразу.
   * Вместо этого она должна возвращать инстанс IO, в котором инкапсулированы все побочные эффекты (открытие-закрытие
   * файлов, запись). Таким образом чистота кода сохраняется. Только когда этот IO инстанс будет выполнен - все побочные
   * эффекты будут запущены.
   *
   * Ещё нужно учесть, что не нужно обрабатывать ошибки внутри IO. После выполнения IO будет содержать ошибку внутри
   * (программа не упадёт), а обработка должна производиться снаружи.
   *
   * Мы используем use на ресурсе. При вызове use ресурс будет создан. При успешном создании - запущена внутренняя часть
   * use. При завершении внутренней части use будут выполнены действия по закрытию ресурсов. Это произойдёт даже если
   * transfer вернёт ошибку, ведь он возвращает IO.
   *
   *
   * @param origin Копируемый файл
   * @param destination Место назначения
   * @return Количество скопированных байт
   */
  def copy(origin: File, destination: File): IO[Long] =
    inputOutputStreams(origin, destination).use { case (in, out) =>
      transfer(in, out)
    }

  /**
   * Метод будет совершать настоящее копирование, когда потоки будут получены. Каким бы ни был результат transfer -
   * после его работы потоки будут закрыты, ведь они вызваны через use.
   *
   * В методе определён цикл, который на каждой итерации читает данные из инпута в буфер, а затем пишет содержимое
   * буфера в аутпут. Ещё она имеет счётчик скопированных байт.
   *
   * Чтобы обеспечить реюз буфера - его нужно определить снаружи основной копирующей петли, поэтому мы создаём
   * дополнительный метод transmit, который и использует этот буфер на самом деле. Таким образом буфер не создаётся
   * на каждом шаге. Он создаётся один раз - и затем уходит во вложенную петлю transmit. Внешняя петля работает
   * только одну итерацию.
   */
  def transfer(origin: InputStream, destination: OutputStream): IO[Long] =
    for {
      buffer <- IO(new Array[Byte](1024 * 10))  // память выделяется только когда IO вызван
      total <- transmit(origin, destination, buffer, 0L)
    } yield total


  /**
   * InputStream и OutputStream инкапсулированы в IO. InputStream становится IO при получении amount. OutputStream
   * становится IO при записи этого amount. IO это монада, поэтому мы можем собирать их в последовательности внутри
   * for-comprehension для создания следующего IO.
   *
   * >> это сахар cats для first.flatMap(_ => second), когда нам безразличен результат предыдущей функции.
   * После каждой операции записи мы рекурсивно вызваем transmit снова, но так как IO стеко-безопасна - мы не
   * задумываемся о переполнении. Каждая операция возвращает count, увеличенный на amount (благодаря аккумулятору).
   */
  def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] =
    for {
      amount <- IO(origin.read(buffer, 0, buffer.length))
      count <- if(amount > -1) IO(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
               else IO.pure(acc)
    } yield count








  /**
   * Вызов inputOutputStreams считается успешным только если оба ресурса внутри были созданы успешно. Так работает
   * обёртка Resource.
   */
  def inputOutputStreams(in: File, out: File): Resource[IO, (InputStream, OutputStream)] =
    for {
      inStream <- inputStream(in)
      outStream <- outputStream(out)
    } yield (inStream, outStream)

  /**
   * Мы хотим быть уверены, что мы закроем файловые потоки когда закончим в них писать. Это и делает Resource. Каждый
   * Resource инкапсулирует действия по открытию и закрытию стримов.
   *
   * Общий метод inputOutputStreams инкапсулирует оба ресурса в один ресурс, который будет доступен как только вложенные
   * ресурсы будут успешно созданы. Resource работает так, что если создание было неуспешным - остальные действия не
   * будут выполнены (даже если создание было неуспешно где-то во вложенном ресурсе).
   *
   * Ещё при использовании Resource мы можем сами обрабатывать ошибки через .handleErrorWith.
   */

  def inputStream(f: File): Resource[IO, FileInputStream] =
    Resource.make {
      IO(new FileInputStream(f))  // получение ресурса
    } { inStream =>
      IO(inStream.close()).handleErrorWith(_ => IO.unit)  // освобождение
    }

  def outputStream(f: File): Resource[IO, FileOutputStream] =
    Resource.make {
      IO(new FileOutputStream(f))  // получение ресурса
    } { outStream =>
      IO(outStream.close()).handleErrorWith(_ => IO.unit)  // освобождение ресурса
    }

  /**
   * Вместо make можно использовать fromAutoCloseable, если мы используем Resource для обёртки над чем-то, что
   * имплементирует java.lang.AutoCloseable. Но в таком коде нельзя указать, что делать с ошибками.
   */
  /*def outputStream(f: File): Resource[IO, FileOutputStream] =
    Resource.fromAutoCloseable(IO(new FileOutputStream(f)))*/
}


object Main {
  def main(args: Array[String]): Unit = {
    val origin: String = args(0)
    val dest: String = args(1)

    val copied: IO[Long] = Copier.copy(new File(origin), new File(dest))

    try {
      copied.unsafeRunSync()
    } catch {
      case e: FileNotFoundException => println(e)
    }


  }


}
