ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.6.4"

ThisBuild / tpolecatOptionsMode := org.typelevel.sbt.tpolecat.DevMode

// todo: can be removed
ThisBuild / resolvers          += Resolver.publishMavenLocal
ThisBuild / evictionErrorLevel := Level.Info

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val Libraries = new {
  val catsCore   = "org.typelevel"   %% "cats-core"   % "2.13.0"
  val catsEffect = "org.typelevel"   %% "cats-effect" % "3.5.7"
  val kafka4s    = "com.github.fd4s" %% "fs2-kafka"   % "3.6.0"

  val grpcNettyShaded = "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion

  val otel4s = Seq(
    "org.typelevel"  %% "otel4s-semconv"                  % "0.12.0-RC3",
    "org.typelevel"  %% "otel4s-semconv-experimental"     % "0.12.0-RC3",
    "org.typelevel"  %% "otel4s-oteljava"                 % "0.12.0-RC3",
    ("org.typelevel" %% "otel4s-oteljava-context-storage" % "0.12-c1f8659-20250318T090939Z-SNAPSHOT").excludeAll(
      "org.typelevel"
    )
  )

  val doobie = Seq(
    "org.tpolecat" %% "doobie-core"     % "1.0.0-RC5",
    "org.tpolecat" %% "doobie-postgres" % "1.0.0-RC5"
  )

  val http4s = Seq(
    "org.http4s" %% "http4s-ember-server" % "0.23.30",
    "org.http4s" %% "http4s-dsl"          % "0.23.30"
  )

  val sttp = Seq(
    "com.softwaremill.sttp.client3" %% "core"                          % "3.10.2",
    "com.softwaremill.sttp.client3" %% "cats"                          % "3.10.2",
    "com.softwaremill.sttp.client3" %% "circe"                         % "3.10.2",
    "com.softwaremill.sttp.client3" %% "opentelemetry-metrics-backend" % "3.10.2"
  )

  val openTelemetry = Seq(
    "io.opentelemetry" % "opentelemetry-exporter-otlp"               % "1.46.0" % Runtime,
    "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % "1.46.0" % Runtime
  )

  val openTelemetryAgent =
    "io.opentelemetry.javaagent" % "opentelemetry-javaagent" % "2.15.0-SNAPSHOT" % Runtime
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
      Libraries.grpcNettyShaded
    ) ++ Libraries.otel4s ++ Libraries.http4s ++ Libraries.openTelemetry
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
      Libraries.grpcNettyShaded
    ) ++ Libraries.otel4s ++ Libraries.sttp ++ Libraries.openTelemetry
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
      Libraries.kafka4s
    ) ++ Libraries.otel4s ++ Libraries.doobie ++ Libraries.openTelemetry
  )
  .dependsOn(protobuf)
