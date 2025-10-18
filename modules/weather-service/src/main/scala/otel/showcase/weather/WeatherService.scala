package otel.showcase.weather

import cats.effect.IO
import fs2.kafka.{Headers, KafkaProducer, ProducerRecord}
import io.grpc.Metadata
import org.slf4j.LoggerFactory
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.context.propagation.TextMapUpdater
import org.typelevel.otel4s.trace.Tracer
import otel.showcase.grpc.*
import otel.showcase.kafka.*
import sttp.client4.*

class WeatherService(
    backend: Backend[IO],
    producer: KafkaProducer[IO, String, Array[Byte]]
)(using Tracer[IO]) extends WeatherFs2Grpc[IO, Metadata] {
  import WeatherService.given

  private val logger = LoggerFactory.getLogger(getClass)

  def checkWeather(request: WeatherRequest, ctx: Metadata): IO[WeatherResponse] =
    IO.defer {
      logger.info(s"Checking forecast for ${request.location}")
      notifyWarehouse(request.location, request.origin) &> checkForecast(request)
    }

  def streamForecast(request: WeatherRequest, ctx: Metadata): fs2.Stream[IO, WeatherResponse] =
    fs2.Stream.eval(checkForecast(request))

  def reportObservations(request: fs2.Stream[IO, WeatherRequest], ctx: Metadata): IO[WeatherResponse] =
    request.evalMap(checkForecast).compile.lastOrError

  def chatWeather(request: fs2.Stream[IO, WeatherRequest], ctx: Metadata): fs2.Stream[IO, WeatherResponse] =
    request.evalMap(checkForecast)

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

  private given TextMapUpdater[Headers] with {
    def updated(carrier: Headers, key: String, value: String): Headers =
      carrier.append(key, value)
  }

}
