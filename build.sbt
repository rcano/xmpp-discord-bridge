name := "xmpp-discord-bridge"

organization := "xmppdiscord"

version := "0.2-SNAPSHOT"

scalaVersion := "2.12.3"

fork := true

scalacOptions ++= Seq("-deprecation", "-feature", "-Xexperimental", "-Yinfer-argument-types", "-Xlint")

libraryDependencies ++= Seq(
  "com.github.scopt" %% "scopt" % "3.6.0",
  "rocks.xmpp" % "xmpp-core-client" % "0.7.4",
  "rocks.xmpp" % "xmpp-extensions-client" % "0.7.4",
  "com.github.jnr" % "jnr-ffi" % "2.1.2",
  "com.chuusai" %% "shapeless" % "2.3.2",
  "net.dv8tion" % "JDA" % "3.2.0_241" exclude ("net.java.dev.jna", "jna")
)
enablePlugins(JavaAppPackaging)
mainClass in Compile := Some("xmppdiscord.XmppDiscordBridge")
javaOptions in Universal ++= Seq("-J-Xmx40m")

resolvers += "jcenter" at "http://jcenter.bintray.com"
resolvers += "jitpack.io" at "https://jitpack.io"
