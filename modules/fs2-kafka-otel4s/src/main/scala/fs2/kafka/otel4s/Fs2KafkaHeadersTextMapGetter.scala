package fs2.kafka.otel4s

import fs2.kafka.Headers
import org.typelevel.otel4s.context.propagation.TextMapGetter

/**
  * Adapter that allows otel4s propagators to read fs2-kafka headers.
  */
class Fs2KafkaHeadersTextMapGetter extends TextMapGetter[Headers] {
  override def get(carrier: Headers, key: String): Option[String] =
    carrier(key).map(_.as[String])

  // otel4s uses this to iterate all available propagation keys.
  override def keys(carrier: Headers): Iterable[String] =
    carrier.toChain.map(_.key).toVector
}
