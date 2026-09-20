// Minimal, Cucumber-free reproduction of the intermittent
// java.nio.file.FileSystemAlreadyExistsException seen during JUnit Platform
// classpath/resource scanning under sbt 2.x.
// See https://github.com/sbt/sbt/issues/9625 for the original report (which
// went through sbt + Cucumber's cucumber-junit-platform-engine).
//
// IMPORTANT: a single-module version of this (16 @Suite classes all in one
// project/classloader, scanning a package in that same project) does NOT
// reproduce the bug -- see README, "Why a single module isn't enough". The
// race needs 16 *separate classloaders* (sbt isolates each subproject's
// dependencies into its own classloader) independently opening the exact
// same physical jar file at once.
//
// Shape of this build:
//  - `shared`: a single library project whose *compiled output* is packaged
//    (as of sbt 2, exportJars defaults to true) into one content-addressed
//    jar. It contains 500 plain, checked-in (see generate-shared-tests.py)
//    do-nothing JUnit Jupiter @Test classes, all in package `shared`.
//  - `consumer1..consumer16`: 16 independent projects, each
//    `.dependsOn(shared)`, each with its own physical copy of a JUnit
//    Platform `@Suite @SelectPackages(Array("shared"))` class
//    (consumerN/src/test/scala/consumertest/RunSharedTests.scala).
//    Discovering that suite makes JUnit Platform scan the classpath for
//    classes in package `shared` -- which means opening `shared`'s
//    compiled-output jar as a java.nio.file.FileSystem.
//
// Since tests run unforked (no `Test / fork`) and sbt runs independent
// projects' Test tasks concurrently, many of these consumers end up
// discovering from the *same physical jar file* (shared's single compiled
// output) *at the same time*, each through its own project-local classloader.
// That's exactly the precondition for the race: see the README.

val scala3 = "3.3.6"

val junitBomVersion = "6.1.3"

lazy val commonSettings = Seq(
  scalaVersion := scala3,
  libraryDependencies += ("org.junit" % "junit-bom" % junitBomVersion).pomOnly(),
  libraryDependencies += "com.github.sbt.junit" % "jupiter-interface" % JupiterKeys.jupiterVersion.value % Test
  // NOTE: deliberately NOT setting `exportJars := false` here -- that's the
  // workaround; this build exists to reproduce the bug it works around. It's
  // confirmed to fix this repro too (see README) -- uncomment to check:
  // , exportJars := false
)

lazy val shared = (project in file("shared"))
  .settings(commonSettings)
  .settings(
    name := "shared",
    libraryDependencies += "org.junit.jupiter" % "junit-jupiter-api" % "*"
    // The 500 do-nothing @Test classes in shared/src/main/scala/shared/ are
    // plain, checked-in files (see generate-shared-tests.py) -- not generated
    // by sbt at build time.
  )

// Same setup for the N consumer projects
def consumerProject(projectName: String): Project =
  Project(projectName, file(projectName))
    .settings(commonSettings)
    .settings(
      name := projectName,
      libraryDependencies ++= Seq(
        "org.junit.platform" % "junit-platform-suite" % "*" % Test,
        "org.junit.jupiter" % "junit-jupiter-engine" % "*" % Test
      )
    )
    .dependsOn(shared)

lazy val consumer1 = consumerProject("consumer1")
lazy val consumer2 = consumerProject("consumer2")
lazy val consumer3 = consumerProject("consumer3")
lazy val consumer4 = consumerProject("consumer4")
lazy val consumer5 = consumerProject("consumer5")
lazy val consumer6 = consumerProject("consumer6")
lazy val consumer7 = consumerProject("consumer7")
lazy val consumer8 = consumerProject("consumer8")
lazy val consumer9 = consumerProject("consumer9")
lazy val consumer10 = consumerProject("consumer10")
lazy val consumer11 = consumerProject("consumer11")
lazy val consumer12 = consumerProject("consumer12")
lazy val consumer13 = consumerProject("consumer13")
lazy val consumer14 = consumerProject("consumer14")
lazy val consumer15 = consumerProject("consumer15")
lazy val consumer16 = consumerProject("consumer16")

lazy val consumers: Seq[ProjectReference] = Seq(
  consumer1, consumer2, consumer3, consumer4,
  consumer5, consumer6, consumer7, consumer8,
  consumer9, consumer10, consumer11, consumer12,
  consumer13, consumer14, consumer15, consumer16
)

lazy val root = (project in file("."))
  .aggregate((consumers :+ (shared: ProjectReference)): _*)
  .settings(
    publish / skip := true
  )
