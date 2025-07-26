package otel.showcase.warehouse

import cats.effect.{IO, IOApp, Resource}
import doobie.Transactor
import doobie.implicits.*
import fs2.kafka.*
import fs2.kafka.consumer.KafkaConsumeChunk.CommitNow
import org.slf4j.LoggerFactory
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.semconv.experimental.attributes.MessagingExperimentalAttributes
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import otel.showcase.kafka.WeatherRequestMessage

import scala.util.chaining.*

object Server extends IOApp.Simple {

  private val logger = LoggerFactory.getLogger(getClass)

  def run: IO[Unit] = {
    given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]

    for {
      otel4s                   <- Resource.eval(OtelJava.global[IO])
      given MeterProvider[IO]  <- Resource.pure(otel4s.meterProvider)
      given TracerProvider[IO] <- Resource.pure(otel4s.tracerProvider)

      _ <- IORuntimeMetrics.register[IO](runtime.metrics, IORuntimeMetrics.Config.default)
      
      transactor <- Resource.pure(createTransactor)
      _          <- Resource.eval(createTables(transactor))
      _          <- Resource.eval(startKafkaConsumer(transactor))
    } yield ()
  }.useForever

  private def startKafkaConsumer(transactor: Transactor[IO])(using TracerProvider[IO]): IO[Unit] = {
    val consumerSettings =
      ConsumerSettings[IO, String, Array[Byte]]
        .withAutoOffsetReset(AutoOffsetReset.Earliest)
        .withBootstrapServers("localhost:9092")
        .withGroupId("group")

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
      Tracer[IO].joinOrRoot(record.headers)(Tracer[IO].currentSpanContext).flatMap { externalCtx =>
        val spanOps = Tracer[IO]
          .spanBuilder("kafka.process")
          .addAttributes(
            MessagingExperimentalAttributes.MessagingKafkaMessageKey(record.key),
            MessagingExperimentalAttributes.MessagingKafkaOffset(record.offset)
          )
          .pipe(builder => externalCtx.fold(builder)(builder.addLink(_)))
          .root
          .build

        spanOps.surround(write(record))
      }

    for {
      given Tracer[IO] <- TracerProvider[IO].get("kafka")
      _ <- KafkaConsumer
        .stream(consumerSettings)
        .subscribeTo("warehouse.request")
        .consumeChunk(chunk => chunk.traverse(record => handleRecord(record)).as(CommitNow))
        .foreverM
    } yield ()
  }

  private def createTransactor(using TracerProvider[IO]): Transactor[IO] =
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

  private given TextMapGetter[Headers] with {
    def get(carrier: Headers, key: String): Option[String] =
      carrier(key).map(_.as[String])

    def keys(carrier: Headers): Iterable[String] =
      carrier.toChain.map(_.key).toVector
  }

}
