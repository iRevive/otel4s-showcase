package otel.showcase.weather

import cats.effect.{IO, IOApp, Resource}
import fs2.grpc.syntax.all.*
import fs2.kafka.{KafkaProducer, ProducerSettings}
import io.grpc.{Server, ServerServiceDefinition}
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}
import otel.showcase.grpc.WeatherFs2Grpc
import sttp.client4.Backend
import sttp.client4.httpclient.cats.HttpClientCatsBackend
import sttp.client4.opentelemetry.otel4s.*

object Server extends IOApp.Simple {

  def run: IO[Unit] = {
    given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]

    for {
      otel4s                   <- Resource.eval(OtelJava.global[IO])
      given MeterProvider[IO]  <- Resource.pure(otel4s.meterProvider)
      given TracerProvider[IO] <- Resource.pure(otel4s.tracerProvider)
      given Tracer[IO]         <- Resource.eval(TracerProvider[IO].get("weather-service"))

      _ <- IORuntimeMetrics.register[IO](runtime.metrics, IORuntimeMetrics.Config.default)

      kafkaProducer <- KafkaProducer.resource(
        ProducerSettings[IO, String, Array[Byte]].withBootstrapServers("localhost:9092")
      )

      backend <- HttpClientCatsBackend.resource[IO]()
      metered <- Otel4sMetricsBackend(backend, Otel4sMetricsConfig.default)
      traced  <- Resource.eval(Otel4sTracingBackend[IO](backend, Otel4sTracingConfig.default))

      weatherService <- buildWeatherService(traced, kafkaProducer)
      server         <- runGrpcServer(weatherService)
      _              <- Resource.eval(IO(server.start()))
    } yield ()
  }.useForever

  private def runGrpcServer(service: ServerServiceDefinition): Resource[IO, Server] =
    NettyServerBuilder
      .forPort(9898)
      .addService(service)
      .resource[IO]

  private def buildWeatherService(
      backend: Backend[IO],
      producer: KafkaProducer[IO, String, Array[Byte]]
  )(using Tracer[IO]): Resource[IO, ServerServiceDefinition] =
    for {
      weatherService <- Resource.pure(new WeatherService(backend, producer))
      server         <- WeatherFs2Grpc.bindServiceResource[IO](weatherService)
    } yield server

}
