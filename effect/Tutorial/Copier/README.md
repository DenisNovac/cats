# Использование Permit и IOApp

Коммит с изменениями и отличиями:

https://github.com/DenisNovac/cats/commit/026bd67d21973d527dc2dd8ca4a3aab7d5096108#diff-e4ab1fef978688248a4d60677134f550


## Отменяемость Resource

IO-инстансы могут быть отменены. Отмена - это мощное средство Cats-effect. Если программист аккуратен - он может использовать это свойство для запуска альтернативного IO-таска после отмены (например, чтобы почистить изменения после работы программы).

IO, созданные через Resource.use могут быть отменены:

```scala
def copy(origin: File, destination: File): IO[Long] =
  inputOutputStreams(origin, destination).use { case (in, out) =>
    transfer(in, out)
}
```

Отмена запускает код, написанный для закрытия ресурса. В нашем случае он закроет потоки. Но что, если отмена произойдёт пока стримы использюутся? Чтобы предотвратить такое повдение, нужно использовать какой-то механизм конкуренции. В нашем случае это будет *semaphore*. 

У семафора есть несколько разрешений (permits). Если в данный момент их нет, то его метод выделения ресурсов (acquire) будет заморожен, пока не вызовется освобождение (release) этого семафора. Важно заметить, что блокирования потоков не происходит.  

Создание потока будет выглядеть следующим образом:

```scala
def inputStream(f: File, guard: Semaphore[IO]): Resource[IO, FileInputStream] =
  Resource.make {
    IO(new FileInputStream(f))
  } { inStream => 
    guard.withPermit {
     IO(inStream.close()).handleErrorWith(_ => IO.unit)
    }
  }

def outputStream(f: File, guard: Semaphore[IO]): Resource[IO, FileOutputStream] =
  Resource.make {
    IO(new FileOutputStream(f))
  } { outStream =>
    guard.withPermit {
      IO(outStream.close()).handleErrorWith(_ => IO.unit)
    }
  }

def inputOutputStreams(in: File, out: File, guard: Semaphore[IO]): Resource[IO, (InputStream, OutputStream)] =
for {
  inStream  <- inputStream(in, guard)
  outStream <- outputStream(out, guard)
} yield (inStream, outStream)
```

Видно, что guard передаётся в стримы извне и используется только в секции освобождения ресурса. `.withPermit` занимает один пермит, запускает IO и освобождает пермит. Кроме того, можно заметить, что везде используется один и тот же guard, переданный в общий метод `inputOutputStreams`.


`copy` будет переписан следующим образом:

```scala
def copy(origin: File, destination: File)(implicit concurrent: Concurrent[IO]): IO[Long] = {
  for {
    guard <- Semaphore[IO](1)
    count <- inputOutputStreams(origin, destination, guard).use { case (in, out) => 
               guard.withPermit(transfer(in, out))
             }
  } yield count
}
```

Перед вызовом `transfer` мы создаём `Semaphore`, который не будет освобождён пока не закончится `transfer`. Вызов `use` позволяет быть уверенным, что семафор будет всегда освобождён (ведь мы написали withPermit в секцию закрытия ресурса, а use всегда закрывает ресурс, либо даже не доходит до этой стадии, тогда пермит не используется). Так как секции освобождения ресурсов в стримах теперь "блокируются" на одном и том же семафоре.

Ещё можно заметить, что `transfer` неотменяем, ведь внутри не используются ресурсы:

```scala
def transfer(origin: InputStream, destination: OutputStream): IO[Long] =
  for {
    buffer <- IO(new Array[Byte](1024 * 10))  // память выделяется только когда IO вызван
    total <- transmit(origin, destination, buffer, 0L)
  } yield total

def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] =
  for {
    amount <- IO(origin.read(buffer, 0, buffer.length))
    count <- if(amount > -1) IO(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
              else IO.pure(acc)
  } yield count
```

## IOApp

Класс запуска переписан следующим образом:


```scala
object Main extends IOApp{

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Аргументы: Источник Назначение"))
           else IO.unit
      orig = new File(args(0))
      dest = new File(args(1))
      count <- Copier.copy(orig, dest)
      _ <- IO(println(s"Count: $count"))

    } yield ExitCode.Success
}

```

Мы помним, что `copy` для вызова требует имплисит `Concurrent[IO]`. `IOApp` имеет его в своём составе автоматически, поэтому его не нужно прописывать вручную. 

`IOApp` - это функциональный эквивалент `App` из стандартной библиотеки Scala. Вместо эффектной `main` мы программируем чистую `run`. Любое нажатие `Ctrl+C` бдет считаться отменой IO. 