package otel.showcase.warehouse

import java.nio.charset.StandardCharsets
import java.util

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.{TextMapPropagator, TextMapSetter}
import org.apache.kafka.clients.consumer.{ConsumerInterceptor, ConsumerRecords, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers

import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*

class TextMapPropagatorInterceptor extends ConsumerInterceptor[Any, Any] {

  private var propagator: TextMapPropagator = uninitialized

  override def onConsume(records: ConsumerRecords[Any, Any]): ConsumerRecords[Any, Any] =
    records.asScala.foreach: record =>
      Option(Context.current).map: context =>
        try propagator.inject(context, record.headers, TextMapPropagatorInterceptor.headerSetter)
        catch case _: Throwable => ()
    records

  override def onCommit(offsets: util.Map[TopicPartition, OffsetAndMetadata]): Unit = ()

  override def close(): Unit = ()

  override def configure(configs: util.Map[String, ?]): Unit =
    propagator = GlobalOpenTelemetry.get.getPropagators.getTextMapPropagator
}

object TextMapPropagatorInterceptor {

  private val headerSetter = new TextMapSetter[Headers] {
    override def set(carrier: Headers, key: String, value: String): Unit =
      carrier.remove(key).add(key, value.getBytes(StandardCharsets.UTF_8)): Unit
  }
}
