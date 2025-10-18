package otel.showcase.gateway

import cats.effect.MonadCancelThrow
import cats.syntax.all.*
import io.grpc.{Metadata, MethodDescriptor}
import fs2.Stream
import fs2.grpc.client.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.context.propagation.TextMapUpdater
import org.typelevel.otel4s.trace.{SpanKind, Tracer, TracerProvider}

private class TracingClientAspect[F[_]: {MonadCancelThrow, Tracer}] extends ClientAspect[F, F, Metadata] {
  import TracingClientAspect.given

  override def visitUnaryToUnaryCallTrailers[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Req,
      run: (Req, Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    propagate(callCtx, "UnaryToUnaryCall", metadata => run(req, metadata))

  override def visitStreamingToUnaryCallTrailers[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    propagate(callCtx, "StreamingToUnaryCallTrailers", metadata => run(req, metadata))

  override def visitUnaryToUnaryCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Req,
      run: (Req, Metadata) => F[Res]
  ): F[Res] =
    propagate(callCtx, "UnaryToUnaryCall", metadata => run(req, metadata))

  override def visitUnaryToStreamingCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Req,
      run: (Req, Metadata) => Stream[F, Res]
  ): Stream[F, Res] =
    Stream.eval(Tracer[F].propagate(new Metadata())).flatMap { metadata =>
      Stream
        .resource(span(callCtx.methodDescriptor, "UnaryToStreamingCall").resource)
        .flatMap { res =>
          metadata.merge(callCtx.ctx)
          run(req, metadata).translate(res.trace)
        }
    }

  override def visitStreamingToUnaryCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => F[Res]
  ): F[Res] =
    propagate(callCtx, "StreamingToUnaryCall", metadata => run(req, metadata))

  override def visitStreamingToStreamingCall[Req, Res](
      callCtx: ClientCallContext[Req, Res, Metadata],
      req: Stream[F, Req],
      run: (Stream[F, Req], Metadata) => Stream[F, Res]
  ): Stream[F, Res] =
    Stream.eval(Tracer[F].propagate(new Metadata())).flatMap { metadata =>
      Stream
        .resource(span(callCtx.methodDescriptor, "StreamingToStreamingCall").resource)
        .flatMap { res =>
          metadata.merge(callCtx.ctx)
          run(req, metadata).translate(res.trace)
        }
    }

  private def propagate[A](callCtx: ClientCallContext[?, ?, Metadata], mode: String, fa: Metadata => F[A]): F[A] =
    MonadCancelThrow[F].uncancelable { poll =>
      Tracer[F].propagate(new Metadata()).flatMap { metadata =>
        metadata.merge(callCtx.ctx)
        span(callCtx.methodDescriptor, mode).surround(poll(fa(metadata)))
      }
    }

  private def span(descriptor: MethodDescriptor[?, ?], mode: String) =
    Tracer[F]
      .spanBuilder(descriptor.getFullMethodName)
      .addAttributes(Attribute("mode", mode))
      .withSpanKind(SpanKind.Client)
      .build
}

object TracingClientAspect {

  def create[F[_]: {MonadCancelThrow, TracerProvider}]: F[ClientAspect[F, F, Metadata]] =
    for {
      given Tracer[F] <- TracerProvider[F].get("fs2-grpc")
    } yield new TracingClientAspect[F]

  private given TextMapUpdater[Metadata] with {
    def updated(carrier: Metadata, key: String, value: String): Metadata = {
      carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value)
      carrier
    }
  }

}
