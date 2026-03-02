package fs2.kafka.otel4s

import cats.effect.IO
import fs2.kafka.{ConsumerRecord, Headers}
import io.opentelemetry.instrumentation.kafkaclients.v2_6.KafkaTelemetry
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer

import scala.jdk.CollectionConverters.*

object Fs2KafkaOtel4s {
  def consumerInterceptorProperties(otelJava: OtelJava[IO]): Map[String, String] =
    KafkaTelemetry
      .create(otelJava.underlying)
      .consumerInterceptorConfigProperties()
      .asScala
      .concat(
        Map(
          ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG -> classOf[Fs2KafakConsumerInterceptor].getName
        )
      )
      .toMap
      .asInstanceOf[Map[String, String]]

  def producerInterceptorProperties(otelJava: OtelJava[IO]): Map[String, String] =
    KafkaTelemetry
      .create(otelJava.underlying)
      .producerInterceptorConfigProperties()
      .asScala
      .toMap
      .asInstanceOf[Map[String, String]]

  given TextMapGetter[Headers] = Fs2KafkaHeadersTextMapGetter()

  def joinOrRoot[K, V, A](record: ConsumerRecord[K, V])(fa: IO[A])(using Tracer[IO]): IO[A] =
    Tracer[IO].joinOrRoot(record.headers)(fa)
}
