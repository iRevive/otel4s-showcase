package otel.showcase.weather

import cats.effect.IO
import fs2.kafka.{KafkaProducer, ProducerRecord}
import io.grpc.Metadata
import org.slf4j.LoggerFactory
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.trace.Tracer
import otel.showcase.grpc.*
import otel.showcase.kafka.*
import sttp.client4.*

import scala.jdk.CollectionConverters.*

class WeatherService(
    backend: Backend[IO],
    producer: KafkaProducer[IO, String, Array[Byte]]
)(using Tracer[IO]) extends WeatherFs2Grpc[IO, Metadata] {
  import WeatherService.given

  private val logger = LoggerFactory.getLogger(getClass)

  def checkWeather(request: WeatherRequest, ctx: Metadata): IO[WeatherResponse] =
    Tracer[IO].joinOrRoot(ctx) {
      Tracer[IO].span("checkWeather").use { _ =>
        logger.info(s"Checking forecast for ${request.location}")
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

      producer.produceOne_(record).void
    }

}

object WeatherService {

  private given TextMapGetter[Metadata] with {
    def get(carrier: Metadata, key: String): Option[String] =
      Option(carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))

    def keys(carrier: Metadata): Iterable[String] =
      carrier.keys().asScala
  }

}
