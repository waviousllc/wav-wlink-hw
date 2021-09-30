// See README.md for license details.

ThisBuild / scalaVersion     := "2.12.12"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "com.github.waviousllc"


lazy val rocketChip = RootProject(file("./rocket-chip"))
lazy val wavCommon  = RootProject(file("./wav-chisel-common-hw"))

lazy val root = (project in file("."))
  .settings(
    name := "wav-wlink-hw",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.0" % "test",
      "edu.berkeley.cs" %% "chisel3" % "3.4.3",
      "edu.berkeley.cs" %% "chiseltest" % "0.3.4" % "test",
      //"edu.berkeley.cs" %% "chiseltest" % "0.3.4",
      "com.github.scopt" %% "scopt" % "4.0.1"
    ),
    scalacOptions ++= Seq(
      "-Xsource:2.11",
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit"
    ),
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.2" cross CrossVersion.full),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )
  .dependsOn(rocketChip, wavCommon)


