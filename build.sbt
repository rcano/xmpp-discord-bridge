name := "xmpp-discord-bridge"

organization := "xmppdiscord"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.8"

fork := true

scalacOptions ++= Seq("-deprecation", "-feature", "-Xexperimental", "-Yinfer-argument-types", "-Xlint")

libraryDependencies ++= Seq(
  "net.databinder.dispatch" %% "dispatch-core" % "0.11.3",
  "org.json4s" %% "json4s-native" % "3.5.0",
  "com.github.austinv11" % "Discord4j" % "2.7.0",
  "com.github.scopt" %% "scopt" % "3.5.0",
  "rocks.xmpp" % "xmpp-core-client" % "0.7.2",
  "rocks.xmpp" % "xmpp-extensions-client" % "0.7.2",
  "com.github.jnr" % "jnr-ffi" % "2.1.2",
  "com.chuusai" %% "shapeless" % "2.3.2"
)
enablePlugins(JavaAppPackaging)
mainClass in Compile := Some("xmppdiscord.XmppDiscordBridge")

resolvers += "jcenter" at "http://jcenter.bintray.com"
resolvers += "jitpack.io" at "https://jitpack.io"
