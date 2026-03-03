package fs2.kafka.otel4s

import cats.effect.IO
import fs2.kafka.{ConsumerRecord, Headers}
import io.opentelemetry.instrumentation.kafkaclients.v2_6.KafkaTelemetry
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer

import scala.jdk.CollectionConverters.*

/**
  * Public helpers to connect fs2-kafka with OpenTelemetry Java agent and otel4s context propagation.
  */
object Fs2KafkaOtel4s {

  /**
    * Producer and consumer use different interception points. On the consumer side we append
    * [[Fs2KafakConsumerInterceptor]] to the Java agent interceptor chain so every consumed record keeps the current
    * trace context in headers for downstream fs2 processing.
    */
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
      // Java returns a raw map compatible with String keys/values for Kafka config.
      .asInstanceOf[Map[String, String]]

  /**
    * Builds producer interceptor config provided by the OpenTelemetry Kafka instrumentation.
    */
  def producerInterceptorProperties(otelJava: OtelJava[IO]): Map[String, String] =
    KafkaTelemetry
      .create(otelJava.underlying)
      .producerInterceptorConfigProperties()
      .asScala
      .toMap
      .asInstanceOf[Map[String, String]]

  /**
    * Reads trace context from fs2-kafka headers.
    */
  given TextMapGetter[Headers] = Fs2KafkaHeadersTextMapGetter()

  /**
    * Continues trace from Kafka headers when present, otherwise starts a new root context.
    */
  def joinOrRoot[K, V, A](record: ConsumerRecord[K, V])(fa: IO[A])(using Tracer[IO]): IO[A] =
    Tracer[IO].joinOrRoot(record.headers)(fa)
}
