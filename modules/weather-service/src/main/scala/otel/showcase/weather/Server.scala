package otel.showcase.weather

import cats.effect.{IO, IOApp, Resource}
import fs2.grpc.syntax.all.*
import fs2.kafka.{KafkaProducer, ProducerSettings}
import io.grpc.{Metadata, Server, ServerServiceDefinition}
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.context.propagation.{TextMapGetter, TextMapUpdater}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.semconv.attributes.HttpAttributes
import org.typelevel.otel4s.trace.{SpanKind, Tracer, TracerProvider}
import otel.showcase.grpc.WeatherFs2Grpc
import sttp.capabilities
import sttp.client3.{DelegateSttpBackend, Request, Response, SttpBackend}
import sttp.client3.httpclient.cats.HttpClientCatsBackend
import sttp.client3.opentelemetry.OpenTelemetryMetricsBackend

import scala.jdk.CollectionConverters.*

object Server extends IOApp.Simple {

  def run: IO[Unit] = {
    given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]

    for {
      otel4s                   <- Resource.eval(OtelJava.global[IO])
      given TracerProvider[IO] <- Resource.pure(otel4s.tracerProvider)
      given Tracer[IO]         <- Resource.eval(TracerProvider[IO].get("weather-service"))

      kafkaProducer <- KafkaProducer.resource(
        ProducerSettings[IO, String, Array[Byte]].withBootstrapServers("localhost:9092")
      )

      backend <- HttpClientCatsBackend.resource[IO]()
      traced  <- Resource.eval(tracedBackend(OpenTelemetryMetricsBackend(backend, otel4s.underlying)))

      weatherService <- buildWeatherService(traced, kafkaProducer)
      server         <- runGrpcServer(weatherService)
      _              <- Resource.eval(IO(server.start()))
    } yield ()
  }.useForever

  private def tracedBackend(backend: SttpBackend[IO, Any])(using TracerProvider[IO]): IO[SttpBackend[IO, Any]] =
    TracerProvider[IO].get("sttp").map { tracer =>
      new DelegateSttpBackend(backend) {
        def send[T, R >: Any & capabilities.Effect[IO]](request: Request[T, R]): IO[Response[T]] =
          tracer
            .spanBuilder(s"${request.method} - ${request.uri}")
            .addAttribute(HttpAttributes.HttpRequestMethod(request.method.toString))
            .withSpanKind(SpanKind.Client)
            .build
            .use { span =>
              backend.send(request).flatTap { response =>
                span.addAttribute(HttpAttributes.HttpResponseStatusCode(response.code.code.toLong))
              }
            }
      }
    }

  private def runGrpcServer(service: ServerServiceDefinition): Resource[IO, Server] =
    NettyServerBuilder
      .forPort(9898)
      .addService(service)
      .resource[IO]

  private def buildWeatherService(
      backend: SttpBackend[IO, Any],
      producer: KafkaProducer[IO, String, Array[Byte]]
  )(using Tracer[IO]): Resource[IO, ServerServiceDefinition] =
    for {
      weatherService <- Resource.pure(new WeatherService(backend, producer))
      server         <- WeatherFs2Grpc.bindServiceResource[IO](weatherService)
    } yield server

  private given TextMapGetter[Metadata] with {
    def get(carrier: Metadata, key: String): Option[String] =
      Option(carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))

    def keys(carrier: Metadata): Iterable[String] =
      carrier.keys().asScala
  }

  private given TextMapUpdater[Metadata] with {
    def updated(carrier: Metadata, key: String, value: String): Metadata = {
      carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value)
      carrier
    }
  }
}
