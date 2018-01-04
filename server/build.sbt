import com.typesafe.sbt.SbtGit.GitKeys
import com.typesafe.sbt.git.ConsoleGitRunner
import sbt._

name := "server"
Revolver.settings

import Dependencies._
import com.typesafe.sbt.SbtGit

lazy val propagateVersionToOtherRepo = taskKey[Unit]("Propagates the version of this project to another github repo.")
lazy val actualBranch = settingKey[String]("the current branch of the git repo")

actualBranch := {
  val branch = sys.env.getOrElse("BRANCH", git.gitCurrentBranch.value)

  if (branch != "master"){
    sys.props += "project.version" -> s"$branch-SNAPSHOT"
  }

  branch
}

propagateVersionToOtherRepo := {
  val branch = actualBranch.value
  println(s"Will try to propagate the version to branch $branch in other repo.")

  val githubClient = GithubClient()
  githubClient.updateFile(
    owner = Env.read("OTHER_REPO_OWNER"),
    repo = Env.read("OTHER_REPO"),
    filePath = Env.read("OTHER_REPO_FILE"),
    branch = branch,
    newContent = version.value
  )
}

// determine the version of our artifacts with sbt-git
lazy val versionSettings = SbtGit.versionWithGit ++ Seq(
  git.baseVersion := "0.8.0",
  git.gitUncommittedChanges := { // the default implementation of sbt-git uses JGit which somehow always returns true here, so we roll our own impl
    import sys.process._
    val gitStatusResult = "git status --porcelain".!!
    if (gitStatusResult.nonEmpty){
      println("Git has uncommitted changes!")
      println(gitStatusResult)
    }
    gitStatusResult.nonEmpty
  }
)

lazy val deploySettings = overridePublishBothSettings ++ Seq(
  credentials += Credentials(
    realm = "packagecloud",
    host = "packagecloud.io",
    userName = "",
    passwd = sys.env.getOrElse("PACKAGECLOUD_PW", sys.error("PACKAGECLOUD_PW env var is not set."))
  ),
  publishTo := Some("packagecloud+https" at "packagecloud+https://packagecloud.io/graphcool/graphcool")//,
//  aether.AetherKeys.aetherWagons := Seq(aether.WagonWrapper("packagecloud+https", "io.packagecloud.maven.wagon.PackagecloudWagon"))
)

lazy val commonSettings = deploySettings ++ versionSettings ++ Seq(
  organization := "cool.graph",
  organizationName := "graphcool",
  scalaVersion := "2.12.3",
  parallelExecution in Test := false,
  publishArtifact in Test := true,
  // We should gradually introduce https://tpolecat.github.io/2014/04/11/scalac-flags.html
  // These needs to separately be configured in Idea
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings"),
  resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
)

def commonBackendSettings(imageName: String) = commonSettings ++ Seq(
  libraryDependencies ++= common,
  imageNames in docker := Seq(
    ImageName(s"graphcool/${imageName}:latest")
  ),
  dockerfile in docker := {
    val appDir    = stage.value
    val targetDir = "/app"

    new Dockerfile {
      from("anapsix/alpine-java")
      entryPoint(s"$targetDir/bin/${executableScriptName.value}")
      copy(appDir, targetDir)
    }
  },
  javaOptions in Universal ++= Seq(
    // -J params will be added as jvm parameters
    "-J-agentlib:jdwp=transport=dt_socket,server=y,address=8000,suspend=n",
    "-J-Dcom.sun.management.jmxremote=true",
    "-J-Dcom.sun.management.jmxremote.local.only=false",
    "-J-Dcom.sun.management.jmxremote.authenticate=false",
    "-J-Dcom.sun.management.jmxremote.ssl=false",
    "-J-Dcom.sun.management.jmxremote.port=3333",
    "-J-Dcom.sun.management.jmxremote.rmi.port=3333",
    "-J-Djava.rmi.server.hostname=localhost",
    "-J-Xmx2560m"
  )
)

def serverProject(name: String, imageName: String): Project = {
  normalProject(name)
    .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)
    .settings(commonBackendSettings(imageName): _*)
    .dependsOn(scalaUtils)
}

def normalProject(name: String): Project = Project(id = name, base = file(s"./$name")).settings(commonSettings: _*)
def libProject(name: String): Project =  Project(id = name, base = file(s"./libs/$name")).settings(commonSettings: _*)

lazy val sharedModels = normalProject("shared-models")
  .dependsOn(gcValues % "compile")
  .dependsOn(jsonUtils % "compile")
  .settings(
  libraryDependencies ++= Seq(
    cuid
  ) ++ joda
)
lazy val deploy = serverProject("deploy", imageName = "graphcool-deploy")
  .dependsOn(sharedModels % "compile")
  .dependsOn(akkaUtils % "compile")
  .dependsOn(metrics % "compile")
  .dependsOn(jvmProfiler % "compile")
  .dependsOn(messageBus % "compile")
  .settings(
    libraryDependencies ++= Seq(
      playJson,
      scalaTest
    )
  )
//  .enablePlugins(BuildInfoPlugin)
//  .settings(
//    buildInfoKeys := Seq[BuildInfoKey](name, version, "imageTag" -> betaImageTag),
//    buildInfoPackage := "build_info"
//  )

lazy val api = serverProject("api", imageName = "graphcool-database")
  .dependsOn(sharedModels % "compile")
  .dependsOn(deploy % "test")
  .dependsOn(messageBus % "compile")
  .dependsOn(akkaUtils % "compile")
  .dependsOn(metrics % "compile")
  .dependsOn(jvmProfiler % "compile")
  .dependsOn(cache % "compile")
  .settings(
    libraryDependencies ++= Seq(
      playJson,
      scalaTest
    )
  )

lazy val subscriptions = serverProject("subscriptions", imageName = "graphcool-subscriptions")
  .dependsOn(api % "compile;test->test")
  .dependsOn(stubServer % "compile")
  .settings(
    libraryDependencies ++= Seq(
      playJson,
      playStreams,
      akkaHttpPlayJson,
      akkaHttpTestKit
    )
  )

lazy val gcValues = libProject("gc-values")
  .settings(libraryDependencies ++= Seq(
    playJson,
    scalactic
  ) ++ joda)

lazy val bugsnag = libProject("bugsnag")
  .settings(libraryDependencies ++= Seq(
    bugsnagClient,
    specs2,
    playJson
  ) ++ jackson)

lazy val akkaUtils = libProject("akka-utils")
  .dependsOn(bugsnag % "compile")
  .dependsOn(scalaUtils % "compile")
  .dependsOn(stubServer % "test")
  .settings(libraryDependencies ++= Seq(
    akka,
    akkaContrib,
    akkaHttp,
    akkaTestKit,
    scalaTest,
    finagle,
    akkaHttpCors,
    playJson,
    specs2,
    caffeine
  ))

lazy val metrics = libProject("metrics")
  .dependsOn(bugsnag % "compile")
  .dependsOn(akkaUtils % "compile")
  .settings(
    libraryDependencies ++= Seq(
      datadogStatsd,
      akkaHttp,
      finagle,
      akka,
      scalaTest
    )
  )

lazy val rabbitProcessor = libProject("rabbit-processor")
  .settings(
    libraryDependencies ++= Seq(
      amqp
    ) ++ jackson
  )
  .dependsOn(bugsnag % "compile")

lazy val messageBus = libProject("message-bus")
  .settings(commonSettings: _*)
  .dependsOn(bugsnag % "compile")
  .dependsOn(akkaUtils % "compile")
  .dependsOn(rabbitProcessor % "compile")
  .settings(libraryDependencies ++= Seq(
    akka,
    specs2,
    scalaTest,
    akkaTestKit,
    playJson
  ))


lazy val jvmProfiler = Project(id = "jvm-profiler", base = file("./libs/jvm-profiler"))
  .settings(commonSettings: _*)
  .dependsOn(metrics % "compile")
  .settings(libraryDependencies += scalaTest)

lazy val graphQlClient = Project(id = "graphql-client", base = file("./libs/graphql-client"))
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= Seq(
    scalaTest,
    playJson,
    akkaHttp
  ))
  .dependsOn(stubServer % "test")
  .dependsOn(akkaUtils % "compile")


lazy val stubServer = libProject("stub-server")

lazy val scalaUtils =
  Project(id = "scala-utils", base = file("./libs/scala-utils"))
    .settings(commonSettings: _*)
    .settings(libraryDependencies ++= Seq(
      scalaTest,
      scalactic
    ))

lazy val jsonUtils =
  Project(id = "json-utils", base = file("./libs/json-utils"))
    .settings(commonSettings: _*)
    .settings(libraryDependencies ++= Seq(
      playJson,
      scalaTest
    ) ++ joda)

lazy val cache =
  Project(id = "cache", base = file("./libs/cache"))
    .settings(commonSettings: _*)
    .settings(libraryDependencies ++= Seq(
      scalaTest,
      caffeine,
      java8Compat,
      jsr305
    ))
lazy val singleServer = serverProject("single-server", imageName = "graphcool-dev")
  .dependsOn(api% "compile")
  .dependsOn(deploy % "compile")
  .dependsOn(subscriptions % "compile")
  .dependsOn(graphQlClient % "compile")

val allServerProjects = List(
  api,
  deploy,
  subscriptions,
  singleServer,
  sharedModels
)

val allLibProjects = List(
  bugsnag,
  akkaUtils,
  metrics,
  rabbitProcessor,
  messageBus,
  jvmProfiler,
  graphQlClient,
  stubServer,
  scalaUtils,
  jsonUtils,
  cache
)

lazy val libs = (project in file("libs")).aggregate(allLibProjects.map(Project.projectToRef): _*)

lazy val root = (project in file("."))
  .aggregate(allServerProjects.map(Project.projectToRef): _*)
  .settings(
    publish := { } // do not publish a JAR for the root project
  )