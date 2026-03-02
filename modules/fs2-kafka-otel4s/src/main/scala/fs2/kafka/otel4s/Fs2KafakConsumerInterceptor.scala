package fs2.kafka.otel4s

import java.nio.charset.StandardCharsets
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

class Fs2KafakConsumerInterceptor extends OpenTelemetryConsumerInterceptor[Any, Any] {

  private var propagator: TextMapPropagator = uninitialized

  override def onConsume(records: ConsumerRecords[Any, Any]): ConsumerRecords[Any, Any] = {
    val instrumentedRecords = super.onConsume(records)
    instrumentedRecords.asScala.foreach { record =>
      Option(Context.current).foreach { context =>
        try propagator.inject(context, record.headers, Fs2KafakConsumerInterceptor.headerSetter)
        catch case _: Throwable => ()
      }
    }
    instrumentedRecords
  }

  override def onCommit(offsets: util.Map[TopicPartition, OffsetAndMetadata]): Unit = ()

  override def close(): Unit = ()

  override def configure(configs: util.Map[String, ?]): Unit = {
    super.configure(configs)
    propagator = GlobalOpenTelemetry.get.getPropagators.getTextMapPropagator
  }
}

object Fs2KafakConsumerInterceptor {

  private val headerSetter = new TextMapSetter[Headers] {
    override def set(carrier: Headers, key: String, value: String): Unit =
      carrier.remove(key).add(key, value.getBytes(StandardCharsets.UTF_8)): Unit
  }
}
