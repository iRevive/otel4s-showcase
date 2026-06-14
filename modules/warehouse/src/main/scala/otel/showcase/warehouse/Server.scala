package otel.showcase.warehouse

import cats.effect.{IO, IOApp, Resource}
import fs2.kafka.*
import fs2.kafka.consumer.KafkaConsumeChunk.CommitNow
import io.opentelemetry.instrumentation.api.incubator.semconv.db.{SqlDialect, SqlQueryAnalyzer}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.slf4j.LoggerFactory
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.otel4s.{QueryAnalyzer, SpanNamer, TracedTransactor, TracingConfig}
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.semconv.attributes.DbAttributes
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

      transactor <- Resource.eval(createTransactor)
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
        .withProperty(
          ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG,
          classOf[TextMapPropagatorInterceptor].getName
        )

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
      Tracer[IO].joinOrRoot(record.headers)(write(record))

    for {
      given Tracer[IO] <- TracerProvider[IO].get("kafka")
      _ <- KafkaConsumer
        .stream(consumerSettings)
        .subscribeTo("warehouse.request")
        .consumeChunk(chunk => chunk.traverse(record => handleRecord(record)).as(CommitNow))
        .foreverM
    } yield ()
  }

  private def createTransactor(using TracerProvider[IO]): IO[Transactor[IO]] = {
    val tx = Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = "jdbc:postgresql://localhost:5432/warehouse",
      user = "user",
      password = "password",
      logHandler = None
    )

    val queryAnalyzer: QueryAnalyzer =
      new QueryAnalyzer {
        private val delegate = SqlQueryAnalyzer.create(true)
        private val dialect  = SqlDialect.DOUBLE_QUOTES_ARE_STRING_LITERALS

        def analyze(sql: String): Option[QueryAnalyzer.QueryMetadata] =
          Option(delegate.analyzeWithSummary(sql, dialect)).map { q =>
            QueryAnalyzer.QueryMetadata(
              queryText = Option(q.getQueryText),
              operationName = Option(q.getOperationName),
              collectionName = Option(q.getCollectionName),
              storedProcedureName = Option(q.getStoredProcedureName),
              querySummary = Option(q.getQuerySummary)
            )
          }
      }

    val config = TracingConfig
      .recommended(DbAttributes.DbSystemNameValue.Postgresql, "warehouse")
      .withQueryAnalyzer(queryAnalyzer)
      .withSpanNamer(
        SpanNamer.fromQueryMetadata
          .orElse(SpanNamer.fromAttribute(DbAttributes.DbQuerySummary))
      )

    TracedTransactor.create[IO](tx, config, None)
  }

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
