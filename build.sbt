ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.1"

ThisBuild / tpolecatOptionsMode := org.typelevel.sbt.tpolecat.DevMode

ThisBuild / semanticdbEnabled := true

ThisBuild / resolvers += "central-snapshots".at("https://central.sonatype.com/repository/maven-snapshots/")

lazy val Libraries = new {
  val catsCore   = "org.typelevel" %% "cats-core"   % "2.13.0"
  val catsEffect = "org.typelevel" %% "cats-effect" % "3.7.0"

  val grpcNettyShaded = "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion

  val fs2 = Seq(
    "co.fs2" %% "fs2-core" % "3.13.0",
    "co.fs2" %% "fs2-io"   % "3.13.0"
  )

  val fs2Grpc = Seq(
    "org.typelevel" %% "fs2-grpc-otel4s-trace" % "3.1.2"
  )

  val fs2Kafka = Seq(
    "org.typelevel"     %% "fs2-kafka"              % "4.1-078c324-SNAPSHOT",
    "io.github.irevive" %% "fs2-kafka-otel4s-trace" % "0.2-7d1f650-SNAPSHOT"
  )

  val otel4s = Seq(
    "org.typelevel" %% "otel4s-oteljava"                 % "1.0.0",
    "org.typelevel" %% "otel4s-oteljava-context-storage" % "1.0.0",
    "org.typelevel" %% "otel4s-semconv"                  % "1.0.0",
    "org.typelevel" %% "otel4s-semconv-experimental"     % "1.0.0",
    "org.typelevel" %% "otel4s-instrumentation-metrics"  % "1.0.0"
  )

  val doobie = Seq(
    "org.typelevel" %% "doobie-core"     % "1.0.0-RC13",
    "org.typelevel" %% "doobie-postgres" % "1.0.0-RC13",
    "org.typelevel" %% "doobie-otel4s"   % "1.0.0-RC13"
  )

  val http4s = Seq(
    "org.http4s" %% "http4s-ember-server" % "0.23.34",
    "org.http4s" %% "http4s-ember-client" % "0.23.34",
    "org.http4s" %% "http4s-dsl"          % "0.23.34"
  )

  val sttp = Seq(
    "com.softwaremill.sttp.client4" %% "core"                                 % "4.0.25",
    "com.softwaremill.sttp.client4" %% "cats"                                 % "4.0.25",
    "com.softwaremill.sttp.client4" %% "circe"                                % "4.0.25",
    "com.softwaremill.sttp.client4" %% "opentelemetry-otel4s-metrics-backend" % "4.0.25",
    "com.softwaremill.sttp.client4" %% "opentelemetry-otel4s-tracing-backend" % "4.0.25"
  )

  val logback = "ch.qos.logback" % "logback-classic" % "1.5.27"

  val openTelemetry = Seq(
    "io.opentelemetry" % "opentelemetry-exporter-otlp"               % "1.63.0" % Runtime,
    "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % "1.63.0" % Runtime
  )

  val openTelemetryInstrumentationJdbc =
    "io.opentelemetry.instrumentation" % "opentelemetry-jdbc" % "2.28.1-alpha"

  val openTelemetryAgent =
    "io.github.irevive" % "otel4s-opentelemetry-javaagent" % "2.28.1" % Runtime
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
    ) ++ Libraries.fs2 ++ Libraries.fs2Grpc ++ Libraries.otel4s ++ Libraries.http4s ++ Libraries.openTelemetry
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
      Libraries.grpcNettyShaded,
      Libraries.logback
    ) ++ Libraries.fs2 ++ Libraries.fs2Grpc ++ Libraries.fs2Kafka ++ Libraries.otel4s ++ Libraries.sttp ++ Libraries.http4s ++ Libraries.openTelemetry
  )
  .dependsOn(protobuf)

// internal request processor
lazy val warehouse = project
  .enablePlugins(JavaAgent)
  .in(file("modules/warehouse"))
  .settings(
    name          := "warehouse",
    run / fork    := true,
    javaOptions   += "-Dotel.service.name=warehouse",
    javaOptions   += "-Dcats.effect.trackFiberContext=true",
    javaAgents    += Libraries.openTelemetryAgent,
    run / envVars += "OTEL_SEMCONV_STABILITY_OPT_IN" -> "database,code",
    libraryDependencies ++= Seq(
      Libraries.catsCore,
      Libraries.catsEffect,
      Libraries.logback,
      Libraries.openTelemetryInstrumentationJdbc
    ) ++ Libraries.fs2 ++ Libraries.fs2Kafka ++ Libraries.otel4s ++ Libraries.doobie ++ Libraries.openTelemetry
  )
  .dependsOn(protobuf)
