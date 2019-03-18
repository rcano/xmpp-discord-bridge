name := "xmpp-discord-bridge"

organization := "xmppdiscord"

version := "0.3-SNAPSHOT"

inThisBuild(Seq(
  scalaVersion := "2.12.8",
  scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-Yinfer-argument-types", "-Yno-adapted-args", "-Xlint", "-Ypartial-unification",
   //"-opt:l:method,inline", "-opt-inline-from:scala.**", "-opt-warnings:_", "-Ywarn-dead-code", "-Ywarn-extra-implicit", "-Ywarn-inaccessible", "-Ywarn-infer-any", "-Ywarn-nullary-override", "-Ywarn-nullary-unit", "-Ywarn-numeric-widen", "-Ywarn-unused:_", "-Ywarn-value-discard"),
   "-opt-warnings:_", "-Ywarn-dead-code", "-Ywarn-extra-implicit", "-Ywarn-inaccessible", "-Ywarn-infer-any", "-Ywarn-nullary-override", "-Ywarn-nullary-unit", "-Ywarn-numeric-widen", "-Ywarn-unused:_", "-Ywarn-value-discard"),
  scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:_", "-opt:_", "-Xlint"),
  javacOptions += "-g",
  fork := true
))


lazy val jfxVersion = "11"
lazy val jfxClassifier = settingKey[String]("jfxClassifier")
jfxClassifier := {
  if (scala.util.Properties.isWin) "win"
  else if (scala.util.Properties.isLinux) "linux"
  else if (scala.util.Properties.isMac) "mac"
  else throw new IllegalStateException(s"Unknown OS: ${scala.util.Properties.osName}")
}

libraryDependencies ++= Seq(
  "com.github.scopt" %% "scopt" % "3.6.0",
  "rocks.xmpp" % "xmpp-core-client" % "0.8.1",
  "rocks.xmpp" % "xmpp-extensions-client" % "0.8.1",
  "net.dv8tion" % "JDA" % "3.8.3_462" exclude ("net.java.dev.jna", "jna"),

  "org.openjfx" % "javafx-graphics" % jfxVersion classifier jfxClassifier.value,
  "org.openjfx" % "javafx-controls" % jfxVersion classifier jfxClassifier.value,
  "org.openjfx" % "javafx-base" % jfxVersion classifier jfxClassifier.value,

  "javax.xml.bind" % "jaxb-api" % "2.3.1",
  "com.sun.xml.bind" % "jaxb-core" % "2.3.0.1",
  "com.sun.xml.bind" % "jaxb-impl" % "2.3.2",
  "javax.activation" % "activation" % "1.1.1",
)
dependsOn(RootProject(file("../tangerine")))

mainClass in Compile := Some("xmppdiscord.XmppDiscordBridge")

resolvers += "jcenter" at "http://jcenter.bintray.com"
resolvers += "jitpack.io" at "https://jitpack.io"

scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:_", "-opt:_", "-Xlint")

lazy val moduleJars = taskKey[Seq[(Attributed[File], java.lang.module.ModuleDescriptor)]]("moduleJars")
moduleJars := {
  val attributedJars = (Compile/dependencyClasspathAsJars).value.filterNot(_.metadata.get(moduleID.key).exists(_.organization == "org.scala-lang"))
  val modules = attributedJars.flatMap { aj =>
    try {
      val module = java.lang.module.ModuleFinder.of(aj.data.toPath).findAll().iterator.next.descriptor
      Some(aj -> module).filter(!_._2.modifiers.contains(java.lang.module.ModuleDescriptor.Modifier.AUTOMATIC))
    } catch { case _: java.lang.module.FindException => None }
  }
  modules
}

enablePlugins(JavaAppPackaging)
mappings in (Compile, packageDoc) := Seq()

Universal/mappings := {
  val prev = (Universal/mappings).value
  val modules = moduleJars.value
  prev.filterNot { case (file, mapping) => modules.exists(_._1.data == file) } ++
  (for { (file, module) <- modules } yield file.data -> s"libmods/${file.data.name}")
}
javaOptions in Universal ++= Seq("-J-Xmx40m")

javaOptions ++= {
  val modules = moduleJars.value
  Seq(
    "-XX:+UnlockExperimentalVMOptions", "-XX:+EnableJVMCI", "-XX:+UseJVMCICompiler", "-Djvmci.Compiler=graal",
    "--add-modules=" + modules.map(_._2.name).mkString(","),
    "--module-path=" + modules.map(_._1.data.getAbsolutePath).mkString(java.io.File.pathSeparator),
    "--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED",
    //"--patch-module=javafx.base=target/scala-2.12/classes",
    //"--patch-module=javafx.controls=target/scala-2.12/classes",
    //"--patch-module=javafx.graphics=target/scala-2.12/classes",
  )
}

javaOptions in Universal ++= Seq(
  "-J-Xmx40m",
  "-J-Xss512k",
  "-J-XX:CICompilerCount=2",
  "-J-XX:VMThreadStackSize=2048",
  "-J-XX:+UnlockExperimentalVMOptions", "-J-XX:+EnableJVMCI", "-J-XX:+UseJVMCICompiler", "-Djvmci.Compiler=graal",
  "-J--add-modules=" + moduleJars.value.map(_._2.name).mkString(","),
  "-J--add-opens=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED",
)

bashScriptExtraDefines ++= {
  val modules = moduleJars.value
  Seq(
    "addJava --module-path=" + modules.map(f => "${app_home}/../libmods/" + f._1.data.name).mkString(":")
  )
}
batScriptExtraDefines ++= {
  val modules = moduleJars.value
  Seq(
    "call :add_java \"--module-path=" + modules.map(f => "%APP_HOME%\\libmods\\" + f._1.data.name).mkString(";") + "\""
  )
}
