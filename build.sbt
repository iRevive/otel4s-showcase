ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.7.1"

ThisBuild / tpolecatOptionsMode := org.typelevel.sbt.tpolecat.DevMode

ThisBuild / semanticdbEnabled := true

lazy val Libraries = new {
  val catsCore   = "org.typelevel"   %% "cats-core"   % "2.13.0"
  val catsEffect = "org.typelevel"   %% "cats-effect" % "3.6.3"
  val kafka4s    = "com.github.fd4s" %% "fs2-kafka"   % "3.9.0"

  val grpcNettyShaded = "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion

  val fs2 = Seq(
    "co.fs2" %% "fs2-core" % "3.12.2",
    "co.fs2" %% "fs2-io"   % "3.12.2"
  )

  val otel4s = Seq(
    "org.typelevel" %% "otel4s-oteljava"                 % "0.14.0",
    "org.typelevel" %% "otel4s-oteljava-context-storage" % "0.14.0",
    "org.typelevel" %% "otel4s-semconv"                  % "0.14.0",
    "org.typelevel" %% "otel4s-semconv-experimental"     % "0.14.0",
    "org.typelevel" %% "otel4s-instrumentation-metrics"  % "0.14.0"
  )

  val doobie = Seq(
    "org.tpolecat" %% "doobie-core"     % "1.0.0-RC10",
    "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC10"
  )

  val http4s = Seq(
    "org.http4s" %% "http4s-ember-server" % "0.23.32",
    "org.http4s" %% "http4s-ember-client" % "0.23.32",
    "org.http4s" %% "http4s-dsl"          % "0.23.32"
  )

  val sttp = Seq(
    "com.softwaremill.sttp.client4" %% "core"                                 % "4.0.12",
    "com.softwaremill.sttp.client4" %% "cats"                                 % "4.0.12",
    "com.softwaremill.sttp.client4" %% "circe"                                % "4.0.12",
    "com.softwaremill.sttp.client4" %% "opentelemetry-otel4s-metrics-backend" % "4.0.12",
    "com.softwaremill.sttp.client4" %% "opentelemetry-otel4s-tracing-backend" % "4.0.12"
  )

  val logback = "ch.qos.logback" % "logback-classic" % "1.5.19"

  val openTelemetry = Seq(
    "io.opentelemetry" % "opentelemetry-exporter-otlp"               % "1.55.0" % Runtime,
    "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % "1.55.0" % Runtime
  )

  val openTelemetryAgent =
    "io.github.irevive" % "otel4s-opentelemetry-javaagent" % "0.0.4" % Runtime
}

lazy val root = project
  .in(file("."))
  .settings(
    name := "otel4s-showcase"
  )
  .aggregate(gateway, warehouse, `weather-service`, protobuf)

lazy val protobuf = project
  .in(file("modules/protobuf"))
  .enablePlugins(Fs2Grpc)
  .settings(
    scalapbCodeGeneratorOptions += CodeGeneratorOption.Scala3Sources,
    scalacOptions               += "-Wconf:src=.*/src_managed/.*:s"
  )

// public gateway
lazy val gateway = project
  .enablePlugins(JavaAgent)
  .in(file("modules/gateway"))
  .settings(
    name        := "gateway",
    run / fork  := true,
    javaOptions += "-Dotel.service.name=gateway",
    javaOptions += "-Dcats.effect.trackFiberContext=true",
    javaAgents  += Libraries.openTelemetryAgent,
    libraryDependencies ++= Seq(
      Libraries.catsCore,
      Libraries.catsEffect,
      Libraries.grpcNettyShaded,
      Libraries.logback
    ) ++ Libraries.fs2 ++ Libraries.otel4s ++ Libraries.http4s ++ Libraries.openTelemetry
  )
  .dependsOn(protobuf)

// internal service
lazy val `weather-service` = project
  .enablePlugins(JavaAgent)
  .in(file("modules/weather-service"))
  .settings(
    name        := "weather-service",
    run / fork  := true,
    javaOptions += "-Dotel.service.name=weather-service",
    javaOptions += "-Dcats.effect.trackFiberContext=true",
    javaAgents  += Libraries.openTelemetryAgent,
    libraryDependencies ++= Seq(
      Libraries.catsCore,
      Libraries.catsEffect,
      Libraries.kafka4s,
      Libraries.grpcNettyShaded,
      Libraries.logback
    ) ++ Libraries.fs2 ++ Libraries.otel4s ++ Libraries.sttp ++ Libraries.http4s ++ Libraries.openTelemetry
  )
  .dependsOn(protobuf)

// internal request processor
lazy val warehouse = project
  .enablePlugins(JavaAgent)
  .in(file("modules/warehouse"))
  .settings(
    name        := "warehouse",
    run / fork  := true,
    javaOptions += "-Dotel.service.name=warehouse",
    javaOptions += "-Dcats.effect.trackFiberContext=true",
    javaAgents  += Libraries.openTelemetryAgent,
    libraryDependencies ++= Seq(
      Libraries.catsCore,
      Libraries.catsEffect,
      Libraries.kafka4s,
      Libraries.logback
    ) ++ Libraries.fs2 ++ Libraries.otel4s ++ Libraries.doobie ++ Libraries.openTelemetry
  )
  .dependsOn(protobuf)
