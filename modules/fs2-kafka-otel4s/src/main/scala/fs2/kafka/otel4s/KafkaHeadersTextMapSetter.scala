package fs2.kafka.otel4s

import java.nio.charset.StandardCharsets

import io.opentelemetry.context.propagation.TextMapSetter
import org.apache.kafka.common.header.Headers

/**
  * Replaces existing header value to avoid duplicates and keep latest context.
  */
class KafkaHeadersTextMapSetter extends TextMapSetter[Headers] {
  override def set(carrier: Headers, key: String, value: String): Unit =
    carrier.remove(key).add(key, value.getBytes(StandardCharsets.UTF_8)): Unit
}
