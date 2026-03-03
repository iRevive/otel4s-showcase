package otel.showcase.warehouse

import cats.effect.{IO, IOApp, Resource}
import doobie.Transactor
import doobie.implicits.*
import fs2.kafka.*
import fs2.kafka.consumer.KafkaConsumeChunk.CommitNow
import fs2.kafka.otel4s.Fs2KafkaOtel4s
import org.slf4j.LoggerFactory
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import otel.showcase.kafka.WeatherRequestMessage

object Server extends IOApp.Simple {

  private val logger = LoggerFactory.getLogger(getClass)

  def run: IO[Unit] = {
    given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]

    for {
      otel4s                   <- Resource.eval(OtelJava.global[IO])
      given MeterProvider[IO]  <- Resource.pure(otel4s.meterProvider)
      given TracerProvider[IO] <- Resource.pure(otel4s.tracerProvider)

      _ <- IORuntimeMetrics.register[IO](runtime.metrics, IORuntimeMetrics.Config.default)

      transactor <- Resource.pure(createTransactor())
      _          <- Resource.eval(createTables(transactor))
      _          <- Resource.eval(startKafkaConsumer(otel4s, transactor))
    } yield ()
  }.useForever

  private def startKafkaConsumer(otelJava: OtelJava[IO], transactor: Transactor[IO])(using
      TracerProvider[IO]
  ): IO[Unit] = {
    val consumerSettings =
      ConsumerSettings[IO, String, Array[Byte]]
        .withAutoOffsetReset(AutoOffsetReset.Earliest)
        .withBootstrapServers("localhost:9092")
        .withGroupId("group")
        .withProperties(Fs2KafkaOtel4s.consumerInterceptorProperties(otelJava))

    def write(record: ConsumerRecord[String, Array[Byte]]): IO[Unit] = IO.defer {
      val message = WeatherRequestMessage.parseFrom(record.value)

      val insert =
        sql"""
            INSERT INTO weather_request (location, origin)
                                 VALUES (${message.location}, ${message.origin});
           """

      logger.info(s"Writing $message into the database")

      insert.update.run.transact(transactor).void
    }

    def handleRecord(record: ConsumerRecord[String, Array[Byte]])(using Tracer[IO]) =
      Fs2KafkaOtel4s.joinOrRoot(record)(write(record))

    for {
      given Tracer[IO] <- TracerProvider[IO].get("kafka")
      _ <- KafkaConsumer
        .stream(consumerSettings)
        .subscribeTo("warehouse.request")
        .consumeChunk(chunk => chunk.traverse(record => handleRecord(record)).as(CommitNow))
        .foreverM
    } yield ()
  }

  private def createTransactor(): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = "jdbc:postgresql://localhost:5432/warehouse",
      user = "user",
      password = "password",
      logHandler = None
    )

  private def createTables(tx: Transactor[IO]): IO[Unit] = {
    val ddl =
      sql"""
          CREATE TABLE IF NOT EXISTS weather_request (
            id         SERIAL      PRIMARY KEY,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            location   TEXT        NOT NULL,
            origin     TEXT        NOT NULL
          );
         """

    ddl.update.run.transact(tx).void
  }
}
