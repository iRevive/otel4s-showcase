package otel.showcase.weather

import cats.effect.MonadCancelThrow
import cats.syntax.all.*
import io.grpc.{Metadata, MethodDescriptor}
import fs2.Stream
import fs2.grpc.server.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.semconv.experimental.attributes.RpcExperimentalAttributes
import org.typelevel.otel4s.trace.{SpanContext, SpanKind, Tracer, TracerProvider}

import scala.util.chaining.*
import scala.jdk.CollectionConverters.*

private class TracingServiceAspect[F[_]: {MonadCancelThrow, Tracer}] extends ServiceAspect[F, F, Metadata] {
  import TracingServiceAspect.given

  def visitUnaryToUnaryCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, Metadata) => F[Res]
  ): F[Res] =
    joinOrRoot(callCtx, "UnaryToUnaryCall", run(req, callCtx.metadata))

  def visitUnaryToStreamingCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, Metadata) => fs2.Stream[F, Res]
  ): fs2.Stream[F, Res] =
    Stream.eval(Tracer[F].joinOrRoot(callCtx.metadata)(Tracer[F].currentSpanContext)).flatMap { ctx =>
      Stream.resource(span(ctx, callCtx.methodDescriptor, "UnaryToStreamingCall").resource).flatMap { res =>
        run(req, callCtx.metadata).translate(res.trace)
      }
    }

  def visitStreamingToUnaryCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: fs2.Stream[F, Req],
      run: (fs2.Stream[F, Req], Metadata) => F[Res]
  ): F[Res] =
    joinOrRoot(callCtx, "StreamingToUnaryCall", run(req, callCtx.metadata))

  def visitStreamingToStreamingCall[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: fs2.Stream[F, Req],
      run: (fs2.Stream[F, Req], Metadata) => fs2.Stream[F, Res]
  ): fs2.Stream[F, Res] =
    Stream.eval(Tracer[F].joinOrRoot(callCtx.metadata)(Tracer[F].currentSpanContext)).flatMap { ctx =>
      Stream.resource(span(ctx, callCtx.methodDescriptor, "StreamingToStreamingCall").resource).flatMap { res =>
        run(req, callCtx.metadata).translate(res.trace)
      }
    }

  def visitUnaryToUnaryCallTrailers[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: Req,
      run: (Req, Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    joinOrRoot(callCtx, "UnaryToUnaryCallTrailers", run(req, callCtx.metadata))

  def visitStreamingToUnaryCallTrailers[Req, Res](
      callCtx: ServiceCallContext[Req, Res],
      req: fs2.Stream[F, Req],
      run: (fs2.Stream[F, Req], Metadata) => F[(Res, Metadata)]
  ): F[(Res, Metadata)] =
    joinOrRoot(callCtx, "StreamingToUnaryCallTrailers", run(req, callCtx.metadata))

  private def joinOrRoot[A](callCtx: ServiceCallContext[?, ?], mode: String, fa: => F[A]): F[A] =
    MonadCancelThrow[F].uncancelable { poll =>
      Tracer[F].joinOrRoot(callCtx.metadata) {
        span(None, callCtx.methodDescriptor, mode).surround(poll(fa))
      }
    }

  private def span(parent: Option[SpanContext], descriptor: MethodDescriptor[?, ?], mode: String) =
    Tracer[F]
      .spanBuilder(descriptor.getFullMethodName)
      .addAttributes(
        RpcExperimentalAttributes.RpcSystem("grpc"),
        RpcExperimentalAttributes.RpcMethod(descriptor.getBareMethodName),
        RpcExperimentalAttributes.RpcService(descriptor.getServiceName),
        Attribute("mode", mode)
      )
      .withSpanKind(SpanKind.Server)
      .pipe(b => parent.fold(b)(b.withParent))
      .build
}

object TracingServiceAspect {

  def create[F[_]: {MonadCancelThrow, TracerProvider}]: F[ServiceAspect[F, F, Metadata]] =
    for {
      given Tracer[F] <- TracerProvider[F].get("fs2-grpc")
    } yield new TracingServiceAspect[F]

  private given TextMapGetter[Metadata] with {
    def get(carrier: Metadata, key: String): Option[String] =
      Option(carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))

    def keys(carrier: Metadata): Iterable[String] =
      carrier.keys().asScala
  }

}
