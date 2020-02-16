# Полиморфизм cats-effect: IO vs F[_]

Коммит с изменениями:

https://github.com/DenisNovac/cats/commit/588c3914f2749746a438a86d85d2bb637d28c821


`IO` способна инкапсулировать побочные эффекты, но возможность создавать синхронные/асинхронные/отменяемые `IO` инстансы приходит из инстанса `Concurrent[IO]`, который реализует `Concurrent[F[_]]`. Это такой тайпкласс, который организует возможность отменять или начинать побочный эффект конкурентно в любом `F`. `Concurrent` расширяет тайплкасс `Async[F[_]]`, а он расширяет тайпкласс `Sync[F[_]]`, который и суспендит побочные эффекты `F`.

`Sync` умеет суспендить побочные эффекты. Мы использовали `IO` для этих целей до текущего момента. Но ведь получается, что мы можем использовать вместо IO аргумент `F[_]: Sync`. На самом деле, так даже рекомендуется делать!

Было:

```scala
def copy(origin: File, destination: File)(implicit concurrent: Concurrent[IO]): IO[Long] =
  for {
    guard <- Semaphore[IO](1)
    count <- inputOutputStreams(origin, destination, guard).use { case (in, out) =>
                guard.withPermit(transfer(in, out))
              }
  } yield count

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

Стало:


```scala
def copy[F[_]: Sync](origin: File, destination: File)(implicit concurrent: Concurrent[F]): F[Long] =
    for {
      guard <- Semaphore[F](1)
      count <- inputOutputStreams(origin, destination, guard).use { case (in, out) =>
                  guard.withPermit(transfer(in, out))
               }
    } yield count

def transfer[F[_]: Sync](origin: InputStream, destination: OutputStream): F[Long] =
  for {
    buffer <- Sync[F].delay(new Array[Byte](1024 * 10))  // память выделяется только когда IO вызван
    total <- transmit(origin, destination, buffer, 0L)
  } yield total

def transmit[F[_]: Sync](origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): F[Long] =
  for {
    amount <- Sync[F].delay(origin.read(buffer, 0, buffer.length))
    count <- if(amount > -1) Sync[F].delay(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
              else Sync[F].pure(acc)
  } yield count
```

Видно, что `IO()` сменилось на `Sync[F].delay`, а `IO.pure` на `Sync[F].pure`. 

Указание использовать, собственно, IO, происходит во время инициализации программы:

```scala
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
```

Точнее, вот тут: `Copier.copy[IO](orig, dest)`. 

Полиморфный код менее ограничен и функции не привязаны к `IO`, но подходят для любого `F[_]` до тех пор пока существует инстанс тайплкасса (`Sync[F[_]]`, `Concurrent[F[_]]` ...) в видимости. Нужный тайпкласс будет выбран из требований нашего кода. 
