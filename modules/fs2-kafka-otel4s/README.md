# fs2-kafka-otel4s

Small helper library to bridge `fs2-kafka`, `otel4s`, and the OpenTelemetry Java Kafka instrumentation.

It provides:
- Kafka interceptor properties for producer and consumer setup
- Header propagation support (`Fs2KafkaHeadersTextMapGetter` and `KafkaHeadersTextMapSetter`) for `otel4s`
- A small helper (`joinOrRoot`) to continue traces from Kafka message headers

## Why this exists

When consuming with `fs2-kafka`, tracing context is typically reconstructed from message headers.  
The OpenTelemetry Java Kafka consumer instrumentation works at Java client level, while fs2 handlers run later in user code.

This module bridges that gap by:
- augmenting consumer interceptor configuration with a custom interceptor
- injecting current context into record headers during consume
- exposing otel4s-compatible extraction utilities for fs2 records

## Dependency

Add the module to your build:

```scala
// TODO
```

## Usage

### Producer configuration

Use OpenTelemetry producer interceptors when building `ProducerSettings`:

```scala
import fs2.kafka.otel4s.Fs2KafkaOtel4s

ProducerSettings[IO, String, Array[Byte]]
  .withBootstrapServers("localhost:9092")
  .withProperties(Fs2KafkaOtel4s.producerInterceptorProperties(otel4s))
```

### Consumer configuration

Use OpenTelemetry consumer interceptors and include the custom `Fs2KafakConsumerInterceptor`:

```scala
import fs2.kafka.otel4s.Fs2KafkaOtel4s

ConsumerSettings[IO, String, Array[Byte]]
  .withBootstrapServers("localhost:9092")
  .withGroupId("group")
  .withProperties(Fs2KafkaOtel4s.consumerInterceptorProperties(otel4s))
```

### Continue trace in record handling

Wrap record processing with `joinOrRoot`:

```scala
def processRecord(record: ConsumerRecord[String, Array[Byte]])(using Tracer[IO]): IO[Unit] =
  Fs2KafkaOtel4s.joinOrRoot(record) {
    // your processing logic
    IO.unit
  }
```

`joinOrRoot` will:
- join an existing trace when propagation headers are present
- create a root context when no headers exist

## Notes

- The consumer helper appends `Fs2KafakConsumerInterceptor` to Kafka interceptor properties.
- Header propagation errors are intentionally ignored in interceptor code to avoid blocking Kafka consumption.
- In services where this library is used for Kafka tracing, disable conflicting automatic Kafka instrumentation (`-Dotel.instrumentation.kafka.enabled=false`).
