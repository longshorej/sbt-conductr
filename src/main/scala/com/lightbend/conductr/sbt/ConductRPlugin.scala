/*
 * Copyright © 2016 Lightbend, Inc. All rights reserved.
 */

package com.lightbend.conductr.sbt

import sbt._
import sbt.Keys._
import sbt.complete.DefaultParsers._
import sbt.complete.Parser
import com.typesafe.sbt.packager.Keys._

import language.postfixOps
import java.io.IOException

import scala.annotation.tailrec
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.sys.process.{ Process, ProcessLogger, stringToProcess }

/**
 * An sbt plugin that interact's with ConductR's controller and potentially other components.
 */
object ConductrPlugin extends AutoPlugin {
  import BundlePlugin.autoImport._
  import ConductrImport._

  // SBT 0.13
  import sbinary.DefaultProtocol.FileFormat

  // SBT 1.0
  import sjsonnew.BasicJsonProtocol._

  libraryDependencies += "com.eed3si9n" %% "sjson-new-spray" % "0.8.2"

  val autoImport = ConductrImport
  import ConductrKeys._

  override def trigger: PluginTrigger =
    allRequirements

  override def globalSettings: Seq[Setting[_]] =
    List(
      dist in Bundle := file(""),
      dist in BundleConfiguration := file(""),

      hasRpLicense := {
        // Same logic as in https://github.com/typesafehub/reactive-platform
        // Doesn't take reactive-platform as a dependency because it is not public.
        val isMeta = (ConductrKeys.isSbtBuild in LocalRootProject).value
        val base = (Keys.baseDirectory in LocalRootProject).value
        val propFile = if (isMeta) base / TypesafePropertiesName else base / "project" / TypesafePropertiesName
        propFile.exists
      }
    )

  override def projectSettings: Seq[Setting[_]] =
    List(
      // Here we try to detect what binary universe we exist inside, so we can
      // accurately grab artifact revisions.
      isSbtBuild := Keys.sbtPlugin.?.value.getOrElse(false) && (Keys.baseDirectory in ThisProject).value.getName == "project",

      discoveredDist := (dist in Bundle).storeAs(discoveredDist).triggeredBy(dist in Bundle).value,
      discoveredConfigDist := (dist in BundleConfiguration).storeAs(discoveredConfigDist).triggeredBy(dist in BundleConfiguration).value,

      conduct := conductTask.evaluated
    )

  override def buildSettings: Seq[Setting[_]] =
    List(
      Keys.aggregate in conduct := false,
      Keys.aggregate in generateInstallationScript := false,
      Keys.aggregate in install := false,
      Keys.aggregate in sandbox := false,

      sandbox := sandboxTask.evaluated,
      sandboxRunTaskInternal := sandboxRunTask(ScopeFilter(inAnyProject, inAnyConfiguration)).value,

      installationData := installationDataTask.value,
      generateInstallationScript := generateInstallationScriptTask().value,
      install := installTask().value
    )

  private final val LatestConductrVersion = "2.0.5"
  private final val LatestConductrDocVersion = LatestConductrVersion.dropRight(1) :+ "x" // 1.0.0 to 1.0.x

  private final val TypesafePropertiesName = "typesafe.properties"
  private final val SandboxRunArgsAttrKey = AttributeKey[SandboxRunArgs]("conductr-sandbox-run-args")
  private final val sandboxRunTaskInternal = taskKey[Unit]("Internal Helper to call sandbox run task.")

  private def installationDataTask: Def.Initialize[Task[Seq[InstallationData]]] = Def.taskDyn {
    val rootProjectStructure = Project.extract(state.value).structure
    val allProjects = rootProjectStructure.allProjects
    val bundleInstallationScriptsTasks =
      allProjects.map { project =>
        val projectRef = ProjectRef(rootProjectStructure.root, project.id)

        val bundleConfiguration = project.configurations.find(c => c.name == Bundle.name)
        val bundleConfigurationConfiguration = project.configurations.find(c => c.name == BundleConfiguration.name)

        (bundleConfiguration, bundleConfigurationConfiguration) match {
          case (Some(b), Some(bc)) =>
            Def.taskDyn {
              val bundleName = (normalizedName in b in projectRef).value
              val bundleFile = (dist in b in projectRef).value.toPath
              val bundleConfigContents = (BundleKeys.bundleConf in bc in projectRef).value
              val bundleConfigSrcDir = (sourceDirectory in bc in projectRef).value / (BundleKeys.configurationName in bc in projectRef).value
              if (bundleConfigContents.nonEmpty || bundleConfigSrcDir.exists()) {
                Def.task {
                  val bundleConfigFile = (dist in bc in projectRef).value.toPath
                  Some(InstallationData(bundleName, Right(bundleFile), Some(bundleConfigFile))): Option[InstallationData]
                }
              } else {
                Def.task[Option[InstallationData]] {
                  Some(InstallationData(bundleName, Right(bundleFile), None))
                }
              }
            }
          case _ =>
            Def.taskDyn(Def.task[Option[InstallationData]](None))
        }
      }

    def fold(a: Seq[InstallationData])(tasks: Seq[Def.Initialize[Task[Option[InstallationData]]]]): Def.Initialize[Task[Seq[InstallationData]]] =
      tasks match {
        case Nil =>
          Def.task(a)
        case x :: xs =>
          Def.taskDyn {
            x.value match {
              case Some(v) => fold(a :+ v)(xs)
              case None    => fold(a)(xs)
            }
          }
      }

    fold(List.empty)(bundleInstallationScriptsTasks)
  }

  private def generateInstallationScriptTask(): Def.Initialize[Task[File]] = Def.task {
    val installDir = (target in LocalRootProject).value
    val installationScript = installDir / "install.sh"
    val installPath = installDir.toPath
    val installationScriptContents = installationData.value.map {
      case InstallationData(bundleName, bundle, bundleConfigFile) =>
        val bundleIdEnv = bundleName.replaceAll("\\W", "_").toUpperCase + "_BUNDLE_ID"
        val bundleArg = InstallationData.nameOrPath(
          bundle match {
            case Left(b)  => Left(b)
            case Right(b) => Right(installPath.relativize(b))
          }
        )
        val bundleConfigArg = bundleConfigFile.map(installPath.relativize).mkString("", "", " ")
        s"""
           |echo "Deploying $bundleName..."
           |$bundleIdEnv=$$(conduct load $bundleArg $bundleConfigArg--long-ids -q)
           |conduct run $${$bundleIdEnv} --no-wait -q
                 """.stripMargin
    }
    IO.write(
      installationScript,
      installationScriptContents.mkString(
        s"""#!/usr/bin/env bash
            |cd "$$( dirname "$${BASH_SOURCE[0]}" )"
            |""".stripMargin,
        "",
        """
          |echo 'Your system is deployed. Running "conduct info" to observe the cluster.'
          |conduct info
          |""".stripMargin
      ),
      Utf8
    )
    println("\nThe ConductR installation script has been successfully created at:\n  " + installationScript)
    installationScript.setExecutable(true)
    installationScript
  }

  private def installTask(): Def.Initialize[Task[Unit]] = Def.task {
    val logger = new ProcessLogger {
      override def err(s: => String): Unit = streams.value.log.error(s)

      override def out(s: => String): Unit = streams.value.log.info(s)

      override def buffer[T](f: => T): T = f
    }

    withProcessHandling {
      val nrOfInstances = "sandbox ps -q".lines_!.size
      if (nrOfInstances > 0) {
        println("Restarting ConductR to ensure a clean state...")
        runProcess(Seq("sandbox", "restart"))
      } else {
        throw new IllegalStateException("Please first start the sandbox using 'sandbox run'.")
      }
    }(sys.error("There was a problem re-starting the sandbox."))

    withProcessHandling {
      waitForConductr()

      installationData.value.foreach {
        case InstallationData(bundleName, bundle, bundleConfigFile) =>
          println(s"Deploying $bundleName...")
          val bundleArg = InstallationData.nameOrPath(bundle)
          val bundleConfigArg = bundleConfigFile.mkString("", "", " ")
          s"conduct load $bundleArg $bundleConfigArg--long-ids -q".lines_!(logger).headOption match {
            case Some(bundleId) =>
              runProcess(Seq("conduct", "run", bundleId, "--no-wait", "-q"), s"Bundle $bundleArg could not run")
            case None =>
              throw new IllegalStateException(s"Bundle $bundleArg could not be loaded")
          }
      }

    }(sys.error("There was a problem installing the project to ConductR."))

    println
    println("""Your system is deployed. Running "conduct info" to observe the cluster.""")
    runProcess(Seq("conduct", "info"))
    println
  }

  private def sandboxTask: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    verifyCliInstallation()

    Parsers.Sandbox.subtask.parsed match {
      case SandboxHelp                 => sandboxHelp()
      case SandboxSubtaskHelp(command) => sandboxSubHelp(command)
      case SandboxRunSubtask(args)     => Project.extract(state.value).runTask(sandboxRunTaskInternal, state.value.put(SandboxRunArgsAttrKey, args))
      case SandboxLogsSubtask          => sandboxLogs()
      case SandboxPsSubtask            => sandboxPs()
      case SandboxStopSubtask          => sandboxStop()
      case SandboxVersionSubtask       => sandboxVersion()
    }
  }

  private def conductTask: Def.Initialize[InputTask[Unit]] = Def.inputTask {
    verifyCliInstallation()

    Parsers.Conduct.subtask.parsed match {
      case ConductHelp                          => conductHelp()
      case ConductSubtaskHelp(command)          => conductSubHelp(command)
      case ConductSubtaskSuccess(command, args) => conductSubtask(command, args)
    }
  }

  private def verifyCliInstallation(): Unit =
    withProcessHandling {
      s"conduct".!(NoProcessLogging)
    }(sys.error(s"The conductr-cli has not been installed. Follow the instructions on http://conductr.lightbend.com/docs/$LatestConductrDocVersion/CLI to install the CLI."))

  private def sandboxHelp(): Unit =
    runProcess(Seq("sandbox", "--help"))

  private def sandboxSubHelp(command: String): Unit =
    runProcess(Seq("sandbox", command, "--help"))

  // FIXME: The filter must be passed in presently: https://github.com/sbt/sbt/issues/1095
  private def sandboxRunTask(filter: ScopeFilter): Def.Initialize[Task[Unit]] = Def.task {
    val projectImageVersion = if (hasRpLicense.value) Some(LatestConductrVersion) else None
    val overrideEndpoints = (BundleKeys.overrideEndpoints in Bundle).?.map(_.flatten.getOrElse(Map.empty)).all(filter).value.flatten
    val endpoints = (BundleKeys.endpoints in Bundle).?.map(_.getOrElse(Map.empty)).all(filter).value.flatten
    val endpointsToUse = if (overrideEndpoints.nonEmpty) overrideEndpoints else endpoints
    val bundlePorts =
      endpointsToUse
        .map(_._2)
        .toSet
        .flatMap { endpoint: Endpoint =>
          endpoint.services.getOrElse(Set.empty).map { uri =>
            if (uri.getHost != null) uri.getPort else uri.getAuthority.drop(1).toInt
          }.collect {
            case port if port >= 0 => port
          }
        }

    val args = state.value.get(SandboxRunArgsAttrKey).getOrElse(SandboxRunArgs())
    sandboxRun(
      conductrImageVersion = args.imageVersion orElse projectImageVersion,
      conductrImage = args.image,
      nrOfContainers = args.nrOfContainers,
      nrOfInstances = args.nrOfInstances,
      features = args.features,
      noDefaultFeatures = args.noDefaultFeatures,
      ports = args.ports ++ bundlePorts,
      logLevel = args.logLevel,
      conductrRoles = args.conductrRoles,
      envs = args.envs,
      envsCore = args.envsCore,
      envsAgent = args.envsAgent,
      args = args.args,
      argsAgent = args.argsAgent,
      argsCore = args.argsCore
    )
  }

  /**
   * Executes the `sandbox run` command of the conductr-cli.
   */
  def sandboxRun(
    conductrImageVersion: Option[String],
    conductrImage: Option[String] = None,
    nrOfContainers: Option[Int] = None,
    nrOfInstances: Option[(Int, Option[Int])] = None,
    features: Seq[Seq[String]] = Seq.empty,
    noDefaultFeatures: Boolean = false,
    ports: Set[Int] = Set.empty,
    logLevel: Option[String] = None,
    conductrRoles: Seq[Set[String]] = Seq.empty,
    envs: Map[String, String] = Map.empty,
    envsCore: Map[String, String] = Map.empty,
    envsAgent: Map[String, String] = Map.empty,
    args: Seq[String] = Seq.empty,
    argsCore: Seq[String] = Seq.empty,
    argsAgent: Seq[String] = Seq.empty
  ): Unit = {
    import Parsers.Sandbox.Flags
    import Parsers.ArgumentConverters._
    import ProcessConverters._

    runProcess(
      Seq("sandbox", "run") ++
        conductrImageVersion.toSeq ++
        conductrImage.withFlag(Flags.image) ++
        nrOfContainers.withFlag(Flags.nrOfContainers) ++
        nrOfInstances.map {
          case (nrOfCores, Some(nrOfAgents)) => s"$nrOfCores:$nrOfAgents"
          case (nrOfAgents, None)            => nrOfAgents.toString
        }.withFlag(Flags.nrOfInstances) ++
        (if (noDefaultFeatures) Seq(Flags.noDefaultFeatures) else Seq.empty) ++
        features.flatMap(Flags.feature +: _) ++
        ports.withFlag(Flags.port) ++
        logLevel.withFlag(Flags.logLevel) ++
        conductrRoles.flatMap(r => if (r.nonEmpty) Flags.conductrRole +: r.toSeq else r) ++
        envs.map(_.asConsolePairArg).withFlag(Flags.env) ++
        envsCore.map(_.asConsolePairArg).withFlag(Flags.envCore) ++
        envsAgent.map(_.asConsolePairArg).withFlag(Flags.envAgent) ++
        args.withFlag(Flags.arg) ++
        argsCore.withFlag(Flags.argCore) ++
        argsAgent.withFlag(Flags.argAgent)
    )
  }

  /**
   * Executes the `sandbox logs` command of the conductr-cli
   */
  def sandboxLogs(): Unit =
    runProcess(Seq("sandbox", "logs"))

  /**
   * Executes the `sandbox ps` command of the conductr-cli
   */
  def sandboxPs(): Unit =
    runProcess(Seq("sandbox", "ps"))

  /**
   * Executes the `sandbox stop` command of the conductr-cli
   */
  def sandboxStop(): Unit =
    runProcess(Seq("sandbox", "stop"))

  /**
   * Executes the `sandbox version` command of the conductr-cli
   */
  def sandboxVersion(): Unit =
    runProcess(Seq("sandbox", "version"))

  /**
   * A convenience function that waits on ConductR to become available.
   *
   * @param duration the max time to wait.
   */
  def waitForConductr(implicit duration: FiniteDuration = 20.seconds): Unit = {
    print("Waiting for ConductR to start")

    val deadline = duration.fromNow

    @tailrec
    def loop(deadline: Deadline): Unit = {
      print(".")
      if (deadline.isOverdue())
        throw new TimeoutException(s"ConductR has not been started within ${duration.toSeconds} seconds!")
      if (s"conduct info".!(NoProcessLogging) != 0) {
        Thread.sleep(500)
        loop(deadline)
      }
    }

    loop(deadline)
    println
  }

  private def conductHelp(): Unit =
    runProcess(Seq("conduct", "--help"))

  private def conductSubHelp(command: String): Unit =
    runProcess(Seq("conduct", command, "--help"))

  private def conductSubtask(command: String, args: Seq[String]): Unit =
    runProcess(Seq("conduct", command) ++ args)

  private def runProcess(args: Seq[String], message: String = ""): Unit = {
    val code = Process(args).!

    if (code != 0)
      sys.error(if (message == "") s"exited with $code" else message)
  }

  private object Parsers {
    final val NSeparator = ' '
    final val PairSeparator = '='
    final val NonDashClass: Parser[Char] =
      charClass(_ != '-', "non-dash character")

    // Sandbox
    object Sandbox {
      import ArgumentConverters._

      val availableFeatures = Set("visualization", "logging", "monitoring")

      // Sandbox parser
      lazy val subtask: Def.Initialize[State => Parser[SandboxSubtask]] = Def.value {
        _ =>
          (Space ~> (
            helpSubtask |
            subHelpSubtask |
            subHelpFlagSubtask |
            logsSubtask |
            psSubtask |
            runSubtask |
            stopSubtask |
            versionSubtask
          )) ?? SandboxHelp
      }

      // Sandbox help command (sandbox --help)
      def helpSubtask: Parser[SandboxHelp.type] =
        (hideAutoCompletion("-h") | token("--help"))
          .map { _ => SandboxHelp }
          .!!! { "Usage: sandbox --help" }

      // This parser is triggering the help of the sandbox sub command if no argument for this command is specified
      // Example: `sandbox run` will execute `sandbox run --help`
      def subHelpSubtask: Parser[SandboxSubtaskHelp] =
        token("run" | token("start"))
          .map(SandboxSubtaskHelp)

      def subHelpFlagSubtask: Parser[SandboxSubtaskHelp] =
        ((token("version") | token("run" | token("start")) | token("restart") | token("stop") | token("ps") | token("logs")) ~ (Space ~ (token("-h") | token("--help"))))
          .map { case (command, _) => SandboxSubtaskHelp(command) }

      def runSubtask: Parser[SandboxRunSubtask] =
        token("run" | token("start")) ~> sandboxRunArgs
          .map { args => SandboxRunSubtask(toRunArgs(args)) }
          .!!!("Usage: sandbox run (start) --help")
      def sandboxRunArgs: Parser[Seq[SandboxRunArg]] =
        (conductrRole | env | envCore | envAgent | arg | argCore | argAgent | image | logLevel | nrOfContainers | nrOfInstances | port | feature | noDefaultFeatures | imageVersion).*
      def toRunArgs(args: Seq[SandboxRunArg]): SandboxRunArgs =
        args.foldLeft(SandboxRunArgs()) {
          case (currentArgs, arg) =>
            arg match {
              case ImageVersionArg(v)   => currentArgs.copy(imageVersion = Some(v))
              case ConductrRoleArg(v)   => currentArgs.copy(conductrRoles = currentArgs.conductrRoles :+ v)
              case EnvArg(v)            => currentArgs.copy(envs = currentArgs.envs + v)
              case EnvCoreArg(v)        => currentArgs.copy(envsCore = currentArgs.envsCore + v)
              case EnvAgentArg(v)       => currentArgs.copy(envsAgent = currentArgs.envsAgent + v)
              case ArgArg(v)            => currentArgs.copy(args = currentArgs.args :+ v)
              case ArgCoreArg(v)        => currentArgs.copy(argsCore = currentArgs.argsCore :+ v)
              case ArgAgentArg(v)       => currentArgs.copy(argsAgent = currentArgs.argsAgent :+ v)
              case ImageArg(v)          => currentArgs.copy(image = Some(v))
              case LogLevelArg(v)       => currentArgs.copy(logLevel = Some(v))
              case NrOfContainersArg(v) => currentArgs.copy(nrOfContainers = Some(v))
              case NrOfInstancesArg(v)  => currentArgs.copy(nrOfInstances = Some(v))
              case PortArg(v)           => currentArgs.copy(ports = currentArgs.ports + v)
              case FeatureArg(v)        => currentArgs.copy(features = currentArgs.features :+ v)
              case NoDefaultFeaturesArg => currentArgs.copy(noDefaultFeatures = true)
            }
        }
      def isRunArg(arg: String, flag: String): Boolean =
        arg.startsWith(flag)

      def logsSubtask: Parser[SandboxLogsSubtask.type] =
        token("logs")
          .map { _ => SandboxLogsSubtask }
          .!!!("Usage: sandbox logs")

      def psSubtask: Parser[SandboxPsSubtask.type] =
        token("ps")
          .map { _ => SandboxPsSubtask }
          .!!!("Usage: sandbox ps")

      def stopSubtask: Parser[SandboxStopSubtask.type] =
        token("stop")
          .map { _ => SandboxStopSubtask }
          .!!!("Usage: sandbox stop")

      def versionSubtask: Parser[SandboxVersionSubtask.type] =
        token("version")
          .map { _ => SandboxVersionSubtask }
          .!!!("Usage: sandbox version")

      // Sandbox command specific arguments
      def imageVersion: Parser[ImageVersionArg] =
        versionNumber("<conductr_version>").map(ImageVersionArg)

      def conductrRole: Parser[ConductrRoleArg] =
        Space ~> (token(Flags.conductrRole) | hideAutoCompletion("-r")) ~> nonArgStringWithText(s"Format: ${Flags.conductrRole} role1 role2").+
          .map(roles => ConductrRoleArg(roles.toSet))

      def env: Parser[EnvArg] =
        Space ~> (token(Flags.env) | hideAutoCompletion("-e")) ~> pairStringWithText(s"Format: ${Flags.env} key=value")
          .map(keyAndValue => EnvArg(keyAndValue.asScalaPairArg))

      def envCore: Parser[EnvCoreArg] =
        Space ~> token(Flags.envCore) ~> pairStringWithText(s"Format: ${Flags.envCore} key=value")
          .map(keyAndValue => EnvCoreArg(keyAndValue.asScalaPairArg))

      def envAgent: Parser[EnvAgentArg] =
        Space ~> token(Flags.envAgent) ~> pairStringWithText(s"Format: ${Flags.envAgent} key=value")
          .map(keyAndValue => EnvAgentArg(keyAndValue.asScalaPairArg))

      def arg: Parser[ArgArg] =
        Space ~> token(Flags.arg) ~> nonArgStringWithText("<argument>")
          .map(arg => ArgArg(arg))

      def argCore: Parser[ArgCoreArg] =
        Space ~> token(Flags.argCore) ~> nonArgStringWithText("<argument>")
          .map(arg => ArgCoreArg(arg))

      def argAgent: Parser[ArgAgentArg] =
        Space ~> token(Flags.argAgent) ~> nonArgStringWithText("<argument>")
          .map(arg => ArgAgentArg(arg))

      def image: Parser[ImageArg] =
        Space ~> (token(Flags.image) | hideAutoCompletion("-i")) ~> nonArgStringWithText("<conductr_image>").map(ImageArg)

      def logLevel: Parser[LogLevelArg] =
        Space ~> (token(Flags.logLevel) | hideAutoCompletion("-l")) ~> nonArgStringWithText("<log-level>").map(LogLevelArg)

      def nrOfContainers: Parser[NrOfContainersArg] =
        Space ~> token(Flags.nrOfContainers) ~> numberWithText("<nr-of-containers>").map(NrOfContainersArg)

      def nrOfInstances: Parser[NrOfInstancesArg] =
        Space ~> (token(Flags.nrOfInstances) | hideAutoCompletion("-n")) ~> instanceNumbers("<nr-of-cores>:<nr-of-agents> | <nr-of-agents>").map(NrOfInstancesArg)

      def port: Parser[PortArg] =
        Space ~> (token(Flags.port) | hideAutoCompletion("-p")) ~> numberWithText("<port>").map(PortArg)

      def feature: Parser[FeatureArg] =
        Space ~> (token(Flags.feature) | hideAutoCompletion("-f")) ~> (featureExamples ~ nonArgStringWithText("<feature_arg>").*)
          .map { case (feature, args) => FeatureArg(feature +: args) }

      def featureExamples: Parser[String] =
        Space ~> token(StringBasic.examples(availableFeatures))

      def noDefaultFeatures: Parser[NoDefaultFeaturesArg.type] =
        Space ~> token(Flags.noDefaultFeatures).map(_ => NoDefaultFeaturesArg)

      def commonArgs: Parser[String] =
        help

      def help: Parser[String] = Space ~> (token("--help") | token("-h"))

      object Flags {
        val imageVersion = "--image-version"
        val conductrRole = "--conductr-role"
        val arg = "--arg"
        val argCore = "--arg-core"
        val argAgent = "--arg-agent"
        val env = "--env"
        val envCore = "--env-core"
        val envAgent = "--env-agent"
        val image = "--image"
        val logLevel = "--log-level"
        val nrOfContainers = "--nr-of-containers"
        val nrOfInstances = "--nr-of-instances"
        val port = "--port"
        val feature = "--feature"
        val noDefaultFeatures = "--no-default-features"
      }
    }

    // Conduct
    object Conduct {
      // Conduct parser
      lazy val subtask: Def.Initialize[State => Parser[ConductSubtask]] = {
        val init = Def.value { (bundle: Option[File], bundleConfig: Option[File], bundleNames: Set[String]) =>
          (Space ~> (
            helpSubtask |
            subHelpSubtask |
            versionSubtask |
            loadSubtask(bundle, bundleConfig) |
            runSubtask(bundleNames) |
            stopSubtask(bundleNames) |
            unloadSubtask(bundleNames) |
            infoSubtask(bundleNames) |
            serviceNamesSubtask |
            aclsSubtask |
            eventsSubtask(bundleNames) |
            logsSubtask(bundleNames) |
            deploySubtask(bundleNames) |
            membersSubtask |
            agentsSubtask |
            loadLicenseSubtask
          )) ?? ConductHelp
        }

        val parserData = Def.setting {
          val ctx = Keys.resolvedScoped.value
          val parser = init.value

          ctx -> parser
        }

        Def.map(parserData) {
          case (ctx, parser) => s: State =>
            val bundle = loadFromContext(discoveredDist, ctx, s)
            val bundleConfig = loadFromContext(discoveredConfigDist, ctx, s)
            val bundleNames =
              withProcessHandling {
                "conduct info"
                  .lines_!(NoProcessLogging)
                  .slice(1, 11) //                         No more than this number of lines please, and no header...
                  .flatMap(_.split("\\s+").slice(1, 2)) // Just the second column i.e. the name
                  .toSet
              }(Set.empty[String])
            parser(bundle, bundleConfig, bundleNames)
        }
      }

      // Conduct help command (conduct --help)
      def helpSubtask: Parser[ConductHelp.type] =
        (hideAutoCompletion("-h") | token("--help"))
          .map { _ => ConductHelp }
          .!!! { "Usage: conduct --help" }

      // This parser is triggering the help of the conduct sub command if no argument for this command is specified
      // Example: `conduct load` will execute `conduct load --help`
      def subHelpSubtask: Parser[ConductSubtaskHelp] =
        (token("load") | token("run" | token("start")) | token("stop") | token("unload") | token("events") |
          token("logs") | token("acls") | token("deploy"))
          .map(ConductSubtaskHelp)

      // Sub command parsers
      def versionSubtask: Parser[ConductSubtaskSuccess] =
        (token("version") ~> commonArgs.?)
          .map { args => ConductSubtaskSuccess("version", optionalArgs(args)) }
          .!!!("Usage: conduct version")

      def loadSubtask(availableBundle: Option[File], availableBundleConfiguration: Option[File]): Parser[ConductSubtaskSuccess] =
        token("load") ~> withArgs(loadArgs)(bundle(availableBundle) ~ bundle(availableBundleConfiguration).?)
          .mapArgs { case (args, (bundle, config)) => ConductSubtaskSuccess("load", optionalArgs(args) ++ Seq(bundle.toString) ++ optionalArgs(config)) }
          .!!! { "Usage: conduct load --help" }
      def loadArgs: Parser[Option[String]] =
        hideAutoCompletion(commonArgs | resolveCacheDir | waitTimeout | noWait).*.map(seqToString).?

      def runSubtask(bundleNames: Set[String]): Parser[ConductSubtaskSuccess] =
        token("run" | token("start")) ~> withArgs(runArgs(bundleNames))(bundleId(bundleNames))
          .mapArgs { case (args, bundle) => ConductSubtaskSuccess("run", optionalArgs(args) ++ Seq(bundle)) }
          .!!!("Usage: conduct run (start) --help")
      def runArgs(bundleNames: Set[String]): Parser[Option[String]] =
        hideAutoCompletion(commonArgs | waitTimeout | noWait | scale | affinity(bundleNames)).*.map(seqToString).?

      def stopSubtask(bundleNames: Set[String]): Parser[ConductSubtaskSuccess] =
        token("stop") ~> withArgs(stopArgs)(bundleId(bundleNames))
          .mapArgs { case (args, bundleId) => ConductSubtaskSuccess("stop", optionalArgs(args) ++ Seq(bundleId)) }
          .!!!("Usage: conduct stop --help")
      def stopArgs: Parser[Option[String]] =
        (waitTimeout | noWait).examples("--no-wait", "--wait-timeout").*.map(seqToString).?

      def unloadSubtask(bundleNames: Set[String]): Parser[ConductSubtaskSuccess] =
        token("unload") ~> withArgs(unloadArgs)(bundleId(bundleNames))
          .mapArgs { case (args, bundleId) => ConductSubtaskSuccess("unload", optionalArgs(args) ++ Seq(bundleId)) }
          .!!!("Usage: conduct unload --help")
      def unloadArgs: Parser[Option[String]] =
        hideAutoCompletion(commonArgs | waitTimeout | noWait).*.map(seqToString).?

      def infoSubtask(bundleNames: Set[String]): Parser[ConductSubtaskSuccess] =
        token("info") ~> withArgs(infoArgs)(bundleId(bundleNames).?)
          .mapArgs { case (args, bundleIdOrName) => ConductSubtaskSuccess("info", optionalArgs(args) ++ bundleIdOrName.fold(Seq.empty[String])(Seq(_))) }
          .!!!("Usage: conduct info --help")
      def infoArgs: Parser[Option[String]] =
        hideAutoCompletion(commonArgs).*.map(seqToString).?

      def serviceNamesSubtask: Parser[ConductSubtaskSuccess] =
        token("service-names" ~> servicesArgs)
          .map { args => ConductSubtaskSuccess("service-names", optionalArgs(args)) }
          .!!!("Usage: conduct service-names")
      def servicesArgs: Parser[Option[String]] =
        hideAutoCompletion(commonArgs).*.map(seqToString).?

      def aclsSubtask: Parser[ConductSubtaskSuccess] =
        token("acls") ~> withArgs(aclArgs)(protocolFamily)
          .mapArgs { case (opts, protocolFamily) => ConductSubtaskSuccess("acls", optionalArgs(opts) ++ Seq(protocolFamily)) }
          .!!!("Usage: conduct acls --help")
      def aclArgs: Parser[Option[String]] =
        hideAutoCompletion(commonArgs).*.map(seqToString).?

      def eventsSubtask(bundleNames: Set[String]): Parser[ConductSubtaskSuccess] =
        (token("events") ~> withArgs(eventsArgs)(bundleId(bundleNames)))
          .mapArgs { case (args, bundleId) => ConductSubtaskSuccess("events", optionalArgs(args) ++ Seq(bundleId)) }
          .!!!("Usage: conduct events --help")
      def eventsArgs: Parser[Option[String]] =
        hideAutoCompletion(commonArgs | waitTimeout | noWait | date | utc | lines).*.map(seqToString).?

      def logsSubtask(bundleNames: Set[String]): Parser[ConductSubtaskSuccess] =
        token("logs") ~> withArgs(logsArgs)(bundleId(bundleNames))
          .mapArgs { case (args, bundleId) => ConductSubtaskSuccess("logs", optionalArgs(args) ++ Seq(bundleId)) }
          .!!!("Usage: conduct logs --help")
      def logsArgs: Parser[Option[String]] =
        hideAutoCompletion(commonArgs | waitTimeout | noWait | date | utc | lines).*.map(seqToString).?

      def deploySubtask(bundleNames: Set[String]): Parser[ConductSubtaskSuccess] =
        token("deploy") ~> withArgs(deployArgs)(bundleId(bundleNames))
          .mapArgs { case (args, bundleId) => ConductSubtaskSuccess("deploy", optionalArgs(args) ++ Seq(bundleId)) }
          .!!!("Usage: conduct deploy --help")
      def deployArgs: Parser[Option[String]] =
        hideAutoCompletion(commonArgs | waitTimeout | noWait | scheme | basePath).*.map(seqToString).?

      def membersSubtask: Parser[ConductSubtaskSuccess] =
        (token("members") ~> commonArgs.?)
          .map { args => ConductSubtaskSuccess("members", optionalArgs(args)) }
          .!!!("Usage: conduct members")

      def agentsSubtask: Parser[ConductSubtaskSuccess] =
        (token("agents") ~> commonArgs.?)
          .map { args => ConductSubtaskSuccess("agents", optionalArgs(args)) }
          .!!!("Usage: conduct agents")

      def loadLicenseSubtask: Parser[ConductSubtaskSuccess] =
        (token("load-license") ~> commonArgs.?)
          .map { args => ConductSubtaskSuccess("load-license", optionalArgs(args)) }
          .!!!("Usage: conduct load-license")

      // Command specific options
      def bundle(file: Option[File]): Parser[URI] =
        Space ~> (basicUri examples file.fold[Set[String]](Set.empty)(f => Set(f.toURI.getPath)))
      def bundleId(bundleNames: Set[String]): Parser[String] =
        Space ~> (StringBasic examples bundleNames)
      def scale: Parser[String] =
        (Space ~> token("--scale" ~ positiveNumber)).map(pairToString)
      def affinity(bundleNames: Set[String]): Parser[String] =
        (Space ~> token("--affinity" ~ bundleId(bundleNames))).map(pairToString)
      def lines: Parser[String] =
        (Space ~> (token("-n" ~ positiveNumber) | token("--lines" ~ positiveNumber))).map(pairToString)
      def date: Parser[String] =
        Space ~> token("--date")
      def utc: Parser[String] =
        Space ~> token("--utc")
      def resolveCacheDir: Parser[String] =
        (Space ~> token("--resolve-cache-dir" ~ basicString)).map(pairToString)
      def waitTimeout: Parser[String] =
        (Space ~> token("--wait-timeout" ~ positiveNumber)).map(pairToString)
      def noWait: Parser[String] =
        Space ~> token("--no-wait")
      def protocolFamily: Parser[String] =
        Space ~> (httpProtocolFamily | tcpProtocolFamily)
      def httpProtocolFamily: Parser[String] =
        token("http")
      def tcpProtocolFamily: Parser[String] =
        token("tcp")
      def scheme: Parser[String] =
        (Space ~> token("--scheme" ~ basicString)).map(pairToString)
      def basePath: Parser[String] =
        (Space ~> token("--base-path" ~ basicString)).map(pairToString)

      // Common optional options
      def commonArgs: Parser[String] =
        help |
          quiet |
          verbose |
          longsIds |
          localConnection |
          apiVersion |
          ip |
          port |
          settingsDir |
          customSettingsFile |
          customPluginsDirs
      def help: Parser[String] = Space ~> (token("--help") | token("-h"))
      def quiet: Parser[String] = Space ~> token("-q")
      def verbose: Parser[String] = Space ~> (token("-v") | token("--verbose"))
      def longsIds: Parser[String] = Space ~> token("--long-ids")
      def localConnection: Parser[String] = Space ~> token("--local-connection")
      def apiVersion: Parser[String] = (Space ~> token("--api-version" ~ positiveNumber)).map(pairToString)
      def ip: Parser[String] = (Space ~> (token("-i" ~ basicString) | token("--ip" ~ basicString))).map(pairToString)
      def port: Parser[String] = (Space ~> (token("-p" ~ positiveNumber) | token("--port" ~ positiveNumber))).map(pairToString)
      def settingsDir: Parser[String] = (Space ~> token("--settings-dir" ~ basicString)).map(pairToString)
      def customSettingsFile: Parser[String] = (Space ~> token("--custom-settings-file" ~ basicString)).map(pairToString)
      def customPluginsDirs: Parser[String] = (Space ~> token("--custom-plugins-dir" ~ basicString)).map(pairToString)

      // Option helpers
      def withArgs[A, B](optionalArgs: Parser[A])(positionalArgs: Parser[B]): Parser[Either[(A, B), (B, A)]] =
        (optionalArgs ~ positionalArgs) || (positionalArgs ~ optionalArgs)
      implicit class ParserOps[A, B](parser: Parser[Either[(A, B), (B, A)]]) {
        def mapArgs[T <: SubtaskSuccess](f: (A, B) => T): Parser[T] =
          parser map {
            case Left((optionalArgs, positionalArgs))  => f(optionalArgs, positionalArgs)
            case Right((positionalArgs, optionalArgs)) => f(optionalArgs, positionalArgs)
          }
      }

      // Converts optional arguments to a `Seq[String]`, meaning the `args` parameter can have 1 to n words
      // Each word is converted to a new element in the `Seq`
      // Example: Some("--help --verbose --scale 5") results in Seq("--help", "--verbose", "--scale", 5)
      // The returned format is ideal to use in `scala.sys.Process()`
      def optionalArgs[T](args: Option[T]): Seq[String] =
        args.fold(Seq.empty[String])(_.toString.split(" "))

      // Convert Tuple[A,B] to String by using a whitespace separator
      def pairToString[A, B](pair: (A, B)): String =
        s"${pair._1} ${pair._2}"

      // Convert Seq[String] to String by using a whitespace separator
      def seqToString(seq: Seq[String]): String =
        seq.mkString(" ")
    }

    // Utility parsers
    def basicString: Parser[String] =
      Space ~> StringBasic
    def basicStringWithText(completionText: String): Parser[String] =
      Space ~> withCompletionText(StringBasic, completionText)
    def nonArgStringWithText(completionText: String): Parser[String] = {
      val nonArgString = identifier(NonDashClass, NotSpaceClass)
      Space ~> withCompletionText(nonArgString, completionText)
    }
    def instanceNumbers(completionText: String): Parser[(Int, Option[Int])] = {
      val nrOfInstances = token(IntBasic, completionText)
      val nrOfAgents = ":" ~> token(IntBasic, completionText)
      Space ~> nrOfInstances ~ nrOfAgents.?
    }
    def isPairString(s: String): Boolean =
      s.split(PairSeparator).length == 2
    def pairStringWithText(completionText: String): Parser[String] = {
      val pairString = StringBasic.filter(isPairString, _ => "Invalid key=value string")
      Space ~> withCompletionText(pairString, completionText)
    }
    def positiveNumber: Parser[Int] =
      Space ~> NatBasic
    def numberWithText(completionText: String): Parser[Int] =
      Space ~> token(IntBasic, completionText)
    def versionNumber(completionText: String): Parser[String] =
      Space ~> token(identifier(charClass(_.isDigit), charClass(c => c == '.' || c == '-' || c.isLetterOrDigit)), completionText)
    def withCompletionText(parser: Parser[String], completionText: String): Parser[String] =
      token(parser, completionText)

    // Hide auto completion in sbt session for the given parser
    def hideAutoCompletion[T](parser: Parser[T]): Parser[T] =
      token(parser, hide = _ => true)

    object ArgumentConverters {
      implicit class StringOps(val self: String) extends AnyVal {
        def asScalaPairArg: (String, String) = {
          if (!isPairString(self))
            sys.error(s"String '$self' can't be converted to a pair by using the separator $PairSeparator.")
          val parts = self.split(PairSeparator)
          parts(0) -> parts(1)
        }
      }

      implicit class PairOps[K, V](val self: (K, V)) extends AnyVal {
        def asConsolePairArg: String =
          s"${self._1}=${self._2}"
      }
    }
  }

  private def withProcessHandling[T](block: => T)(exceptionHandler: => T): T =
    try {
      block
    } catch {
      case ioe: IOException => exceptionHandler
    }

  private trait SubtaskSuccess
  private sealed trait SandboxSubtask
  private case class SandboxRunSubtask(args: SandboxRunArgs) extends SandboxSubtask with SubtaskSuccess
  private object SandboxLogsSubtask extends SandboxSubtask with SubtaskSuccess
  private object SandboxPsSubtask extends SandboxSubtask with SubtaskSuccess
  private object SandboxStopSubtask extends SandboxSubtask with SubtaskSuccess
  private object SandboxVersionSubtask extends SandboxSubtask with SubtaskSuccess
  private case object SandboxHelp extends SandboxSubtask
  private case class SandboxSubtaskHelp(command: String) extends SandboxSubtask

  private sealed trait ConductSubtask
  private case class ConductSubtaskSuccess(command: String, args: Seq[String]) extends ConductSubtask with SubtaskSuccess
  private case object ConductHelp extends ConductSubtask
  private case class ConductSubtaskHelp(command: String) extends ConductSubtask

  private sealed trait SandboxRunArg extends Any
  private case class ImageVersionArg(value: String) extends AnyVal with SandboxRunArg
  private case class ConductrRoleArg(value: Set[String]) extends AnyVal with SandboxRunArg
  private case class EnvArg(value: (String, String)) extends AnyVal with SandboxRunArg
  private case class EnvCoreArg(value: (String, String)) extends AnyVal with SandboxRunArg
  private case class EnvAgentArg(value: (String, String)) extends AnyVal with SandboxRunArg
  private case class ArgArg(value: String) extends AnyVal with SandboxRunArg
  private case class ArgCoreArg(value: String) extends AnyVal with SandboxRunArg
  private case class ArgAgentArg(value: String) extends AnyVal with SandboxRunArg
  private case class ImageArg(value: String) extends AnyVal with SandboxRunArg
  private case class LogLevelArg(value: String) extends AnyVal with SandboxRunArg
  private case class NrOfContainersArg(value: Int) extends AnyVal with SandboxRunArg
  private case class NrOfInstancesArg(value: (Int, Option[Int])) extends AnyVal with SandboxRunArg
  private case class PortArg(value: Int) extends AnyVal with SandboxRunArg
  private case class FeatureArg(value: Seq[String]) extends AnyVal with SandboxRunArg
  private case object NoDefaultFeaturesArg extends SandboxRunArg
  private case class SandboxRunArgs(
    imageVersion: Option[String] = None,
    conductrRoles: Seq[Set[String]] = Seq.empty,
    envs: Map[String, String] = Map.empty,
    envsCore: Map[String, String] = Map.empty,
    envsAgent: Map[String, String] = Map.empty,
    args: Seq[String] = Seq.empty,
    argsCore: Seq[String] = Seq.empty,
    argsAgent: Seq[String] = Seq.empty,
    image: Option[String] = None,
    logLevel: Option[String] = None,
    nrOfContainers: Option[Int] = None,
    nrOfInstances: Option[(Int, Option[Int])] = None,
    ports: Set[Int] = Set.empty,
    features: Seq[Seq[String]] = Seq.empty,
    noDefaultFeatures: Boolean = false
  )

  private object NoProcessLogging extends ProcessLogger {
    def out(s: => String): Unit = {}

    def err(s: => String): Unit = {}

    def buffer[T](f: => T): T = f
  }

  private[sbt] object ProcessConverters {
    implicit class OptionOps[T](val self: Option[T]) extends AnyVal {
      def withFlag(flag: String): Seq[String] =
        self.toSeq.withFlag(flag)
    }

    implicit class TraversableOps[T](val self: Traversable[T]) extends AnyVal {
      def withFlag(flag: String): Seq[String] =
        self.toSeq.flatMap { e =>
          val s = e.toString

          // python's argparse can't parse "--somearg" "-Dsomevalue", must be specified as "--somearg=-Dsomevalue"
          // see http://bugs.python.org/issue9334

          if (s.startsWith("-"))
            Seq(s"$flag=$s")
          else
            Seq(flag, s)
        }
    }
  }
}
