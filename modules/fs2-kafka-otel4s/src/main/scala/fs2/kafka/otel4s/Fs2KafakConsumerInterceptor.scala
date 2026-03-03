package fs2.kafka.otel4s

import java.util

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{TextMapPropagator, TextMapSetter}
import io.opentelemetry.instrumentation.kafkaclients.v2_6.internal.OpenTelemetryConsumerInterceptor
import org.apache.kafka.clients.consumer.{ConsumerRecords, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers

import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*

/**
  * Extends OpenTelemetry Java consumer interception by mirroring the current context into record headers.
  *
  * fs2-kafka processing happens later in user code, where only headers are available for extraction. This interceptor
  * makes sure every consumed record carries trace propagation data.
  */
class Fs2KafakConsumerInterceptor extends OpenTelemetryConsumerInterceptor[Any, Any] {

  private val headerSetter: TextMapSetter[Headers] = KafkaHeadersTextMapSetter()
  private var propagator: TextMapPropagator        = uninitialized

  override def onConsume(records: ConsumerRecords[Any, Any]): ConsumerRecords[Any, Any] = {
    // Keep parent behavior from the Java instrumentation, then enrich headers for fs2 side.
    val instrumentedRecords = super.onConsume(records)
    instrumentedRecords.asScala.foreach { record =>
      Option(Context.current).foreach { context =>
        // Propagation failures must not block Kafka consumption.
        try propagator.inject(context, record.headers, headerSetter)
        catch case _: Throwable => ()
      }
    }
    instrumentedRecords
  }

  override def onCommit(offsets: util.Map[TopicPartition, OffsetAndMetadata]): Unit = ()

  override def close(): Unit = ()

  // Reads globally configured propagators (W3C tracecontext/baggage by default).
  override def configure(configs: util.Map[String, ?]): Unit = {
    super.configure(configs)
    propagator = GlobalOpenTelemetry.get.getPropagators.getTextMapPropagator
  }
}
