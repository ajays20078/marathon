import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.LocalDate

import com.amazonaws.auth.{EnvironmentVariableCredentialsProvider, InstanceProfileCredentialsProvider}
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.packager.docker.Cmd
import mesosphere.maven.MavenSettings.{loadM2Credentials, loadM2Resolvers}
import mesosphere.raml.RamlGeneratorPlugin

import scalariform.formatter.preferences.{AlignArguments, AlignParameters, AlignSingleLineCaseStatements, CompactControlReadability, DanglingCloseParenthesis, DoubleIndentClassDeclaration, FormatXml, FormattingPreferences, IndentSpaces, IndentWithTabs, MultilineScaladocCommentsStartOnFirstLine, PlaceScaladocAsterisksBeneathSecondAsterisk, Preserve, PreserveSpaceBeforeArguments, SpaceBeforeColon, SpaceInsideBrackets, SpaceInsideParentheses, SpacesAroundMultiImports, SpacesWithinPatternBinders}

lazy val SerialIntegrationTest = config("serial-integration") extend Test
lazy val IntegrationTest = config("integration") extend Test
lazy val UnstableTest = config("unstable") extend Test
lazy val UnstableIntegrationTest = config("unstable-integration") extend Test

def formattingTestArg(target: File) = Tests.Argument("-u", target.getAbsolutePath, "-eDFG")

credentials ++= loadM2Credentials(streams.value.log)
resolvers ++= loadM2Resolvers(sLog.value)

resolvers += Resolver.sonatypeRepo("snapshots")
addCompilerPlugin("org.psywerx.hairyfotr" %% "linter" % "0.1.17")

lazy val formatSettings = SbtScalariform.scalariformSettings ++ Seq(
  ScalariformKeys.preferences := FormattingPreferences()
    .setPreference(AlignArguments, false)
    .setPreference(AlignParameters, false)
    .setPreference(AlignSingleLineCaseStatements, false)
    .setPreference(CompactControlReadability, false)
    .setPreference(DoubleIndentClassDeclaration, true)
    .setPreference(DanglingCloseParenthesis, Preserve)
    .setPreference(FormatXml, true)
    .setPreference(IndentSpaces, 2)
    .setPreference(IndentWithTabs, false)
    .setPreference(MultilineScaladocCommentsStartOnFirstLine, false)
    .setPreference(PlaceScaladocAsterisksBeneathSecondAsterisk, true)
    .setPreference(PreserveSpaceBeforeArguments, true)
    .setPreference(SpacesAroundMultiImports, true)
    .setPreference(SpaceBeforeColon, false)
    .setPreference(SpaceInsideBrackets, false)
    .setPreference(SpaceInsideParentheses, false)
    .setPreference(SpacesWithinPatternBinders, true)
)

lazy val commonSettings = inConfig(SerialIntegrationTest)(Defaults.testTasks) ++
  inConfig(IntegrationTest)(Defaults.testTasks) ++
  inConfig(UnstableTest)(Defaults.testTasks) ++
  inConfig(UnstableIntegrationTest)(Defaults.testTasks) ++
  aspectjSettings ++ Seq(
  autoCompilerPlugins := true,
  organization := "mesosphere.marathon",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq(scalaVersion.value),
  scalacOptions in Compile ++= Seq(
    "-encoding", "UTF-8",
    "-target:jvm-1.8",
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Xfuture",
    "-Xlog-reflective-calls",
    "-Xlint",
    //FIXME: CORE-977 and MESOS-7368 are filed and need to be resolved to re-enable this
    // "-Xfatal-warnings",
    "-Yno-adapted-args",
    "-Ywarn-numeric-widen",
    //"-Ywarn-dead-code", We should turn this one on soon
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    //"-Ywarn-unused", We should turn this one on soon
    "-Ywarn-unused-import",
    //"-Ywarn-value-discard", We should turn this one on soon.
    "-Yclosure-elim",
    "-Ydead-code"
  ),
  javacOptions in Compile ++= Seq(
    "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation"
  ),
  resolvers ++= Seq(
    "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/",
    "Apache Shapshots" at "https://repository.apache.org/content/repositories/snapshots/",
    "Mesosphere Public Repo" at "https://downloads.mesosphere.com/maven"
  ),
  cancelable in Global := true,
  publishTo := Some(s3resolver.value(
    "Mesosphere Public Repo (S3)",
    s3("downloads.mesosphere.io/maven")
  )),
  s3credentials := new EnvironmentVariableCredentialsProvider() | new InstanceProfileCredentialsProvider(),

  testListeners := Seq(new PhabricatorTestReportListener(target.value / "phabricator-test-reports")),
  parallelExecution in Test := true,
  testForkedParallel in Test := true,
  testOptions in Test := Seq(formattingTestArg(target.value / "test-reports"),
    Tests.Argument("-l", "mesosphere.marathon.IntegrationTest",
      "-l", "mesosphere.marathon.SerialIntegrationTest",
      "-l", "mesosphere.marathon.UnstableTest",
      "-y", "org.scalatest.WordSpec")),
  fork in Test := true,

  parallelExecution in UnstableTest := true,
  testForkedParallel in UnstableTest := true,
  testOptions in UnstableTest := Seq(formattingTestArg(target.value / "test-reports" / "unstable"), Tests.Argument(
    "-l", "mesosphere.marathon.IntegrationTest",
    "-l", "mesosphere.marathon.SerialIntegrationTest",
    "-y", "org.scalatest.WordSpec")),
  fork in UnstableTest := true,

  fork in SerialIntegrationTest := true,
  testOptions in SerialIntegrationTest := Seq(formattingTestArg(target.value / "test-reports" / "serial-integration"),
    Tests.Argument(
      "-n", "mesosphere.marathon.SerialIntegrationTest",
      "-l", "mesosphere.marathon.UnstableTest",
      "-y", "org.scalatest.WordSpec")),
  parallelExecution in SerialIntegrationTest := false,
  testForkedParallel in SerialIntegrationTest := false,

  fork in IntegrationTest := true,
  testOptions in IntegrationTest := Seq(formattingTestArg(target.value / "test-reports" / "integration"),
    Tests.Argument(
      "-n", "mesosphere.marathon.IntegrationTest",
      "-l", "mesosphere.marathon.SerialIntegrationTest",
      "-l", "mesosphere.marathon.UnstableTest",
      "-y", "org.scalatest.WordSpec")),
  parallelExecution in IntegrationTest := true,
  testForkedParallel in IntegrationTest := true,
  concurrentRestrictions in IntegrationTest := Seq(Tags.limitAll(math.max(1, java.lang.Runtime.getRuntime.availableProcessors() / 2))),
  test in IntegrationTest := {
    (test in IntegrationTest).value
    (test in SerialIntegrationTest).value
  },

  fork in UnstableIntegrationTest := true,
  testOptions in UnstableIntegrationTest := Seq(formattingTestArg(target.value / "test-reports" / "unstable-integration"),
    Tests.Argument(
      "-n", "mesosphere.marathon.IntegrationTest",
      "-n", "mesosphere.marathon.SerialIntegrationTest",
      "-y", "org.scalatest.WordSpec")),
  parallelExecution in UnstableIntegrationTest := true,
  testForkedParallel in UnstableIntegrationTest := true,

  scapegoatVersion := "1.3.0",

  coverageMinimum := 62,
  coverageFailOnMinimum := true,

  fork in run := true,
  AspectjKeys.aspectjVersion in Aspectj := "1.8.10",
  AspectjKeys.inputs in Aspectj += compiledClasses.value,
  products in Compile := (products in Aspectj).value,
  products in Runtime := (products in Aspectj).value,
  products in Compile := (products in Aspectj).value,
  AspectjKeys.showWeaveInfo := true,
  AspectjKeys.verbose := true,
  // required for AJC compile time weaving
  javacOptions in Compile += "-g",
  javaOptions in run ++= (AspectjKeys.weaverOptions in Aspectj).value,
  javaOptions in Test ++= (AspectjKeys.weaverOptions in Aspectj).value,
  git.useGitDescribe := true,
  // TODO: There appears to be a bug where uncommitted changes is true even if nothing is committed.
  git.uncommittedSignifier := None
)


lazy val packageDebianUpstart = taskKey[File]("Create debian upstart package")
lazy val packageDebianSystemV = taskKey[File]("Create debian systemv package")
lazy val packageDebianSystemd = taskKey[File]("Create debian systemd package")
lazy val packageRpmSystemV = taskKey[File]("Create rpm systemv package")
lazy val packageRpmSystemd = taskKey[File]("create rpm systemd package")

lazy val packagingSettings = Seq(
  packageSummary := "Scheduler for Apache Mesos",
  packageDescription := "Cluster-wide init and control system for services running on\\\n\tApache Mesos",
  maintainer := "Mesosphere Package Builder <support@mesosphere.io>",
  debianPackageDependencies in Debian := Seq("java8-runtime-headless", "lsb-release", "unzip", s"mesos (>= ${Dependency.V.MesosDebian})"),
  rpmVendor := "mesosphere",
  rpmLicense := Some("Apache 2"),
  version in Rpm := {
    val releasePattern = """^(\d+)\.(\d+)\.(\d+)$""".r
    val snapshotPattern = """^(\d+).(\d+)\.(\d+)-SNAPSHOT-\d+-g(\w+)""".r
    version.value match {
      case releasePattern(major, minor, patch) => s"$major.$minor.$patch"
      case snapshotPattern(major, minor, patch, commit) => s"$major.$minor.$patch${LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)}git$commit"
    }
  },
  daemonStdoutLogFile := None,
  debianChangelog in Debian := Some(baseDirectory.value / "changelog.md"),
  rpmRequirements in Rpm := Seq("coreutils", "unzip", "java >= 1:1.8.0"),
  dockerBaseImage := Dependency.V.OpenJDK,
  dockerExposedPorts := Seq(8080),
  dockerRepository := Some("mesosphere"),
  daemonUser in Docker := "root",
  dockerCommands ++= Seq(
    Cmd("RUN", "apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF && " +
        "echo deb http://repos.mesosphere.com/debian jessie-testing main | tee -a /etc/apt/sources.list.d/mesosphere.list && " +
        "echo deb http://repos.mesosphere.com/debian jessie main | tee -a /etc/apt/sources.list.d/mesosphere.list && " +
        "apt-get update && " +
        s"apt-get install --no-install-recommends -y --force-yes mesos=${Dependency.V.MesosDebian} && " +
        "apt-get clean"
    ),
    Cmd("RUN", "chown -R daemon:daemon ."),
    Cmd("USER", "daemon")
  ),
  bashScriptExtraDefines +=
    """
      |for env_op in `env | grep -v ^MARATHON_APP | grep ^MARATHON_ | awk '{gsub(/MARATHON_/,""); gsub(/=/," "); printf("%s%s ", "--", tolower($1)); for(i=2;i<=NF;i++){printf("%s ", $i)}}'`; do
      |  addApp "$env_op"
      |done
    """.stripMargin,
  packageDebianUpstart := {
    val debianFile = (packageBin in Debian).value
    val output = target.value / "packages" / s"upstart-${debianFile.getName}"
    IO.move(debianFile, output)
    streams.value.log.info(s"Moved debian ${(serverLoading in Debian).value} package $debianFile to $output")
    output
  },
  packageDebianSystemV := {
    val debianFile = (packageBin in Debian).value
    val output = target.value / "packages" /  s"sysvinit-${debianFile.getName}"
    IO.move(debianFile, output)
    streams.value.log.info(s"Moved debian ${(serverLoading in Debian).value} package $debianFile to $output")
    output
  },
  packageDebianSystemd := {
    val debianFile = (packageBin in Debian).value
    val output = target.value / "packages" /  s"systemd-${debianFile.getName}"
    IO.move(debianFile, output)
    streams.value.log.info(s"Moving debian ${(serverLoading in Debian).value} package $debianFile to $output")
    output
  },
  packageRpmSystemV := {
    val rpmFile = (packageBin in Rpm).value
    val output = target.value / "packages" /  s"sysvinit-${rpmFile.getName}"
    IO.move(rpmFile, output)
    streams.value.log.info(s"Moving rpm ${(serverLoading in Rpm).value} package $rpmFile to $output")
    output
  },
  packageRpmSystemd := {
    val rpmFile = (packageBin in Rpm).value
    val output = target.value / "packages" /  s"systemd-${rpmFile.getName}"
    IO.move(rpmFile, output)
    streams.value.log.info(s"Moving rpm ${(serverLoading in Rpm).value} package $rpmFile to $output")
    output
  },
  mappings in (Compile, packageDoc) := Seq()
)

addCommandAlias("packageAll", ";universal:packageBin; universal:packageXzTarball; docker:publishLocal; packageDebian; packageRpm")

addCommandAlias("packageDebian",  ";set serverLoading in Debian := com.typesafe.sbt.packager.archetypes.ServerLoader.SystemV" +
  ";packageDebianSystemV" +
  ";set serverLoading in Debian := com.typesafe.sbt.packager.archetypes.ServerLoader.Upstart" +
  ";packageDebianUpstart" +
  ";set serverLoading in Debian := com.typesafe.sbt.packager.archetypes.ServerLoader.Systemd" +
  ";packageDebianSystemd")

addCommandAlias("packageRpm",  ";set serverLoading in Rpm := com.typesafe.sbt.packager.archetypes.ServerLoader.SystemV" +
  ";packageRpmSystemV" +
  ";set serverLoading in Rpm := com.typesafe.sbt.packager.archetypes.ServerLoader.Systemd" +
  ";packageRpmSystemd")


lazy val `plugin-interface` = (project in file("plugin-interface"))
    .enablePlugins(GitBranchPrompt, CopyPasteDetector, BasicLintingPlugin)
    .configs(SerialIntegrationTest)
    .configs(IntegrationTest)
    .configs(UnstableTest)
    .configs(UnstableIntegrationTest)
    .settings(commonSettings : _*)
    .settings(formatSettings : _*)
    .settings(
      name := "plugin-interface",
      libraryDependencies ++= Dependencies.pluginInterface
    )

lazy val marathon = (project in file("."))
  .configs(SerialIntegrationTest)
  .configs(IntegrationTest)
  .configs(UnstableTest)
  .configs(UnstableIntegrationTest)
  .enablePlugins(GitBranchPrompt, JavaServerAppPackaging, DockerPlugin, DebianPlugin, RpmPlugin, JDebPackaging,
    CopyPasteDetector, RamlGeneratorPlugin, BasicLintingPlugin, GitVersioning)
  .dependsOn(`plugin-interface`)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .settings(packagingSettings: _*)
  .settings(
    unmanagedResourceDirectories in Compile += file("docs/docs/rest-api"),
    libraryDependencies ++= Dependencies.marathon,
    sourceGenerators in Compile += (ramlGenerate in Compile).taskValue,
    scapegoatIgnoredFiles ++= Seq(s"${sourceManaged.value.getPath}/.*"),
    mainClass in Compile := Some("mesosphere.marathon.Main"),
    packageOptions in (Compile, packageBin) ++= Seq(
      Package.ManifestAttributes("Implementation-Version" -> version.value ),
      Package.ManifestAttributes("Scala-Version" -> scalaVersion.value ),
      Package.ManifestAttributes("Git-Commit" -> git.gitHeadCommit.value.getOrElse("unknown") )
    )
  )


lazy val `mesos-simulation` = (project in file("mesos-simulation"))
  .configs(SerialIntegrationTest)
  .configs(IntegrationTest)
  .configs(UnstableTest)
  .configs(UnstableIntegrationTest)
  .enablePlugins(GitBranchPrompt, CopyPasteDetector, BasicLintingPlugin)
  .settings(commonSettings: _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    name := "mesos-simulation"
  )

// see also, benchmark/README.md
lazy val benchmark = (project in file("benchmark"))
  .configs(SerialIntegrationTest)
  .configs(IntegrationTest)
  .configs(UnstableTest)
  .configs(UnstableIntegrationTest)
  .enablePlugins(JmhPlugin, GitBranchPrompt, CopyPasteDetector, BasicLintingPlugin)
  .settings(commonSettings : _*)
  .settings(formatSettings: _*)
  .dependsOn(marathon % "compile->compile; test->test")
  .settings(
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit),
    libraryDependencies ++= Dependencies.benchmark,
    generatorType in Jmh := "asm"
  )
