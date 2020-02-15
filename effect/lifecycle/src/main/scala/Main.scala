import cats.effect._
import cats.syntax.all._

object MainIO extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = ???

}


class MainModule {

  def start()(implicit ...): IO[Unit] = {
    for {
      config <- Config.loadSync
      transactorResource = DatabaseModule.createTransactor(config.database)
      _ <- transactorResource.use(transactor => program(config, transactor))
    } yield ()
  }

  def program(appConfig: AppConfig, transactor: Transactor[IO])(implicit ...): IO[Unit] = {
    for {
      dietModule <- DietModule.createSync(transactor)
      trainingModule <- TrainingModule.createSync(transactor)
      scheduleModule <- ScheduleModule.createSync(transactor, dietModule.dietRepository, trainingModule.trainingRepository)
      api = new HttpApi(dietModule.dietController, trainingModule.trainingController, scheduleModule.scheduleController)
      _ <- BlazeServerBuilder[IO].bindHttp(appConfig.http.port, appConfig.http.host).withHttpApp(api.httpApp).serve.compile.drain
    } yield ()
  }
}

// пример модуля
class DietModule(transactor: Transactor[IO]) {
  lazy val dietRepository = new DietRepository(transactor)
  lazy val dietService = new DietService(dietRepository)
  lazy val dietController = new DietController(dietService)
}

object DietModule {
  def createSync(transactor: Transactor[IO]): IO[DietModule] = IO.delay(new DietModule(transactor))
}