package fs2.kafka.otel4s

import fs2.kafka.Headers
import org.typelevel.otel4s.context.propagation.TextMapGetter

class Fs2KafkaHeadersTextMapGetter extends TextMapGetter[Headers] {
  override def get(carrier: Headers, key: String): Option[String] =
    carrier(key).map(_.as[String])

  override def keys(carrier: Headers): Iterable[String] =
    carrier.toChain.map(_.key).toVector
}
