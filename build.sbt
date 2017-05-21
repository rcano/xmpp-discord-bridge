name := "xmpp-discord-bridge"

organization := "xmppdiscord"

version := "0.1-SNAPSHOT"

scalaVersion := "2.12.2"

fork := true

scalacOptions ++= Seq("-deprecation", "-feature", "-Xexperimental", "-Yinfer-argument-types", "-Xlint")

libraryDependencies ++= Seq(
  "com.github.austinv11" % "Discord4j" % "2.8.1",
  "com.github.scopt" %% "scopt" % "3.5.0",
  "rocks.xmpp" % "xmpp-core-client" % "0.7.4",
  "rocks.xmpp" % "xmpp-extensions-client" % "0.7.4",
  "com.github.jnr" % "jnr-ffi" % "2.1.2",
  "com.chuusai" %% "shapeless" % "2.3.2"
)
enablePlugins(JavaAppPackaging)
mainClass in Compile := Some("xmppdiscord.XmppDiscordBridge")

resolvers += "jcenter" at "http://jcenter.bintray.com"
resolvers += "jitpack.io" at "https://jitpack.io"
