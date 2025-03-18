package otel.showcase.gateway

import cats.data.Kleisli
import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.*
import fs2.grpc.syntax.all.*
import io.grpc.{ManagedChannel, Metadata}
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import org.http4s.{Headers, HttpApp, HttpRoutes, Request, Response}
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.context.propagation.{TextMapGetter, TextMapUpdater}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.oteljava.context.{Context, IOLocalContextStorage}
import org.typelevel.otel4s.semconv.attributes.HttpAttributes
import org.typelevel.otel4s.trace.{SpanKind, Tracer, TracerProvider}
import otel.showcase.grpc.{WeatherFs2Grpc, WeatherRequest}

object Server extends IOApp.Simple {

  private val logger = Slf4jLogger.getLogger[IO]

  def run: IO[Unit] = {
    given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO]

    for {
      otel4s                   <- Resource.eval(OtelJava.global[IO])
      given TracerProvider[IO] <- Resource.pure(otel4s.tracerProvider)
      given Tracer[IO]         <- Resource.eval(TracerProvider[IO].get("otel.showcase.gateway"))

      grpcChannel <- buildGrpcChannel
      weatherGrpc <- WeatherFs2Grpc.stubResource[IO](grpcChannel)

      httpApp <- Resource.eval(tracingMiddleware(routes(weatherGrpc).orNotFound))
      server  <- startHttpSever(httpApp)
      _       <- Resource.eval(logger.info(s"Bound server at ${server.address}"))
    } yield ()
  }.useForever

  private def startHttpSever(httpApp: HttpApp[IO]) =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"9000")
      .withHttpApp(httpApp)
      .build

  private def tracingMiddleware(httpApp: HttpApp[IO])(using TracerProvider[IO]): IO[HttpApp[IO]] =
    TracerProvider[IO].get("org.http4s.otel4s.middleware.server").map { tracer =>
      Kleisli { (req: Request[IO]) =>
        IO.uncancelable { poll =>
          tracer.joinOrRoot(req.headers) {
            tracer
              .spanBuilder(s"${req.method} - ${req.uri}")
              .withSpanKind(SpanKind.Server)
              .build
              .use { span =>
                poll(httpApp.run(req)).flatTap { response =>
                  span.addAttribute(HttpAttributes.HttpResponseStatusCode(response.status.code.toLong))
                }
              }
          }
        }
      }
    }

  private def routes(weatherGrpc: WeatherFs2Grpc[IO, Metadata])(using Tracer[IO]): HttpRoutes[IO] =
    HttpRoutes.of {
      case req @ GET -> Root / "weather" / location =>
        val request = WeatherRequest(location, req.from.map(_.toString).getOrElse("unknown"))

        Tracer[IO].span("gRPC: checkWeather").surround {
          for {
            _            <- logger.info(s"Request: $request")
            metadata     <- Tracer[IO].propagate(new Metadata())
            grpcResponse <- weatherGrpc.checkWeather(request, metadata)
          } yield Response().withEntity(grpcResponse.forecast)
        }

      case GET -> Root / "health" =>
        IO(Response())
    }

  private val buildGrpcChannel: Resource[IO, ManagedChannel] =
    NettyChannelBuilder
      .forAddress("127.0.0.1", 9898)
      .usePlaintext()
      .resource[IO]

  private given TextMapGetter[Headers] with {
    def get(carrier: Headers, key: String): Option[String] =
      carrier.get(CIString(key)).map(_.head.value)

    def keys(carrier: Headers): Iterable[String] =
      carrier.headers.view.map(_.name).distinct.map(_.toString).toSeq
  }

  private given TextMapUpdater[Metadata] with {
    def updated(carrier: Metadata, key: String, value: String): Metadata = {
      carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value)
      carrier
    }
  }

}
