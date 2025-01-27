package otel.showcase.weather

import cats.effect.IO
import fs2.kafka.{Headers, KafkaProducer, ProducerRecord}
import io.grpc.Metadata
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.context.propagation.{TextMapGetter, TextMapUpdater}
import org.typelevel.otel4s.trace.Tracer
import otel.showcase.grpc.*
import otel.showcase.kafka.*
import sttp.client3.*

import scala.jdk.CollectionConverters.*

class WeatherService(
    backend: SttpBackend[IO, Any],
    producer: KafkaProducer[IO, String, Array[Byte]]
)(using Tracer[IO]) extends WeatherFs2Grpc[IO, Metadata] {
  import WeatherService.given

  def checkWeather(request: WeatherRequest, ctx: Metadata): IO[WeatherResponse] =
    Tracer[IO].joinOrRoot(ctx) {
      Tracer[IO].span("checkWeather").use { span =>
        println("check forecast: " + request + " ctx: " + ctx + " " + span)
        notifyWarehouse(request.location, request.origin) &> checkForecast(request)
      }
    }

  private def checkForecast(request: WeatherRequest): IO[WeatherResponse] =
    Tracer[IO]
      .span("checkForecast", Attribute("location", request.location))
      .surround {
        for {
          response <- backend.send(basicRequest.get(uri"https://wttr.in/${request.location}?format=3"))
        } yield WeatherResponse(response.body.merge)
      }

  private def notifyWarehouse(location: String, origin: String): IO[Unit] =
    Tracer[IO].span("notify-warehouse").surround {
      val record = ProducerRecord(
        "warehouse.request",
        "weather",
        WeatherRequestMessage(location, origin).toByteArray
      )

      for {
        headers <- Tracer[IO].propagate(Headers.empty)
        _       <- producer.produceOne_(record.withHeaders(headers))
      } yield ()
    }

}

object WeatherService {

  private given TextMapGetter[Metadata] with {
    def get(carrier: Metadata, key: String): Option[String] =
      Option(carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))

    def keys(carrier: Metadata): Iterable[String] =
      carrier.keys().asScala
  }

  private given TextMapUpdater[Headers] with {
    def updated(carrier: Headers, key: String, value: String): Headers =
      carrier.append(key, value)
  }

}
