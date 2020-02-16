import cats.effect.{Concurrent, ExitCode, IO, IOApp, Resource, Sync}
import cats.implicits._
import java.io._

import cats.effect.concurrent.Semaphore

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
  def copy[F[_]: Sync](origin: File, destination: File)(implicit concurrent: Concurrent[F]): F[Long] =
    for {
      guard <- Semaphore[F](1)
      count <- inputOutputStreams(origin, destination, guard).use { case (in, out) =>
                  guard.withPermit(transfer(in, out))
               }
    } yield count


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
  def transfer[F[_]: Sync](origin: InputStream, destination: OutputStream): F[Long] =
    for {
      buffer <- Sync[F].delay(new Array[Byte](1024 * 10))  // память выделяется только когда IO вызван
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
  def transmit[F[_]: Sync](origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): F[Long] =
    for {
      amount <- Sync[F].delay(origin.read(buffer, 0, buffer.length))
      count <- if(amount > -1) Sync[F].delay(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
               else Sync[F].pure(acc)
    } yield count








  /**
   * Вызов inputOutputStreams считается успешным только если оба ресурса внутри были созданы успешно. Так работает
   * обёртка Resource.
   */
  def inputOutputStreams[F[_]: Sync](in: File, out: File, guard: Semaphore[F]): Resource[F, (InputStream, OutputStream)] =
    for {
      inStream <- inputStream(in, guard)
      outStream <- outputStream(out, guard)
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

  def inputStream[F[_]: Sync](f: File, guard: Semaphore[F]): Resource[F, FileInputStream] =
    Resource.make {
      Sync[F].delay(new FileInputStream(f))  // получение ресурса
    } { inStream =>
      guard.withPermit {
        Sync[F].delay(inStream.close()).handleErrorWith(_ => Sync[F].unit)  // освобождение
      }
    }

  def outputStream[F[_]: Sync](f: File, guard: Semaphore[F]): Resource[F, FileOutputStream] =
    Resource.make {
      Sync[F].delay(new FileOutputStream(f))  // получение ресурса
    } { outStream =>
      guard.withPermit {
        Sync[F].delay(outStream.close()).handleErrorWith(_ => Sync[F].unit)  // освобождение ресурса
      }

    }

  /**
   * Вместо make можно использовать fromAutoCloseable, если мы используем Resource для обёртки над чем-то, что
   * имплементирует java.lang.AutoCloseable. Но в таком коде нельзя указать, что делать с ошибками.
   */
  /*def outputStream(f: File): Resource[IO, FileOutputStream] =
    Resource.fromAutoCloseable(IO(new FileOutputStream(f)))*/
}


object Main extends IOApp{

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Аргументы: Источник Назначение"))
           else IO.unit
      orig = new File(args(0))
      dest = new File(args(1))
      count <- Copier.copy[IO](orig, dest)
      _ <- IO(println(s"Count: $count"))

    } yield ExitCode.Success
}
