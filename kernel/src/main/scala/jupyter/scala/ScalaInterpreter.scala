package jupyter.scala

import java.io.File

import ammonite.pprint
import ammonite.interpreter._
import ammonite.api.{IvyConstructor, ImportData, BridgeConfig}

import jupyter.api._
import jupyter.kernel.interpreter
import jupyter.kernel.interpreter.DisplayData
import jupyter.kernel.protocol.Output.LanguageInfo
import jupyter.kernel.protocol.ParsedMessage

import com.github.alexarchambault.ivylight.{ClasspathFilter, Ivy, Resolver}
import org.apache.ivy.plugins.resolver.DependencyResolver

object ScalaInterpreter {

  trait InterpreterDefaults extends interpreter.Interpreter {
    override def resultDisplay = true // Displaying results directly, not under Jupyter "Out" prompt

    val languageInfo = LanguageInfo(
      name=s"scala${scalaBinaryVersion.filterNot(_ == '.')}",
      version = scalaVersion,
      codemirror_mode = "text/x-scala",
      file_extension = "scala",
      mimetype = "text/x-scala",
      pygments_lexer = "scala"
    )

    override val implementation = ("jupyter-scala", s"${BuildInfo.version} (scala $scalaVersion)")
    override val banner =
      s"""Jupyter Scala ${BuildInfo.version} (Ammonite ${BuildInfo.ammoniteVersion} fork) (Scala $scalaVersion)
         |Start dependencies: ${startIvys.map{case (org, name, ver) => s"  $org:$name:$ver"}.mkString("\n", "\n", "")}
       """.stripMargin
  }

  def bridgeConfig(publish: => Option[Publish[Evidence]],
                   currentMessage: => Option[ParsedMessage[_]],
                   startJars: Seq[File] = Nil,
                   startIvys: Seq[(String, String, String)] = Nil,
                   jarMap: File => File = identity,
                   startResolvers: Seq[DependencyResolver] = Seq(Resolver.localRepo, Resolver.defaultMaven),
                   pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig,
                   colors: ColorSet = ColorSet.Default): BridgeConfig =
    BridgeConfig(
      "object ReplBridge extends jupyter.api.APIHolder",
      "ReplBridge",
      NamesFor[API].map{case (n, isImpl) => ImportData(n, n, "", "ReplBridge.shell", isImpl)}.toSeq ++
        NamesFor[IvyConstructor.type].map{case (n, isImpl) => ImportData(n, n, "", "ammonite.api.IvyConstructor", isImpl)}.toSeq,
      it => println(it.asInstanceOf[Iterator[String]].mkString)
    ) {
        var api: FullAPI = null

        (intp, cls) =>
          if (api == null)
            api = new APIImpl(intp, publish, currentMessage, startJars, startIvys, jarMap, startResolvers, colors, pprintConfig)

          APIHolder.initReplBridge(cls.asInstanceOf[Class[APIHolder]], api)
    }

  val wrap =
    Wrap(decls => s"ReplBridge.shell.Internal.combinePrints(${decls.map(WebDisplay(_)).mkString(", ")})", classWrap = true)

  val scalaVersion = scala.util.Properties.versionNumberString
  val scalaBinaryVersion = scala.util.Properties.versionNumberString.split('.').take(2).mkString(".")

  val startIvys = Seq(
    ("org.scala-lang", "scala-library", scalaVersion),
    ("com.github.alexarchambault.jupyter", s"jupyter-scala-api_$scalaVersion", BuildInfo.version)
  )

  val startCompilerIvys = startIvys ++ Seq(("org.scala-lang", "scala-compiler", scalaVersion))

  val startResolvers = 
    Seq(Resolver.localRepo, Resolver.defaultMaven) ++ {
      if (BuildInfo.version endsWith "-SNAPSHOT") Seq(Resolver.sonatypeRepo("snapshots")) else Seq()
    }


  lazy val jarMap = Classes.jarMap(getClass.getClassLoader)


  def classPathOrIvy(allDeps: String, ivys: Seq[(String, String, String)]) =
    Classes.fromClasspath(allDeps, getClass.getClassLoader) match {
      case Right(jars) => (jars.toSeq, Nil)

      case Left(missing) =>
        println(s"Cannot find the following dependencies on the classpath:\n${missing.mkString("\n")}")
        println("Resolving shared dependencies with Ivy")
        Ivy.resolve(ivys, startResolvers).toList
          .map(jarMap)
          .distinct
          .filter(_.exists())
          .partition(_.getName endsWith ".jar")
    }

  lazy val (startJars, startDirs) =
    classPathOrIvy(KernelBuildInfo.apiDeps, startIvys)
  lazy val (startCompilerJars, startCompilerDirs) =
    classPathOrIvy(KernelBuildInfo.compilerDeps, startCompilerIvys)

  lazy val startClassLoader: ClassLoader =
    new ClasspathFilter(getClass.getClassLoader, (Classes.bootClasspath ++ startJars ++ startDirs).toSet)
  lazy val startCompilerClassLoader: ClassLoader =
    new ClasspathFilter(getClass.getClassLoader, (Classes.bootClasspath ++ startCompilerJars ++ startCompilerDirs).toSet)

  def apply(startJars: => Seq[File] = startJars,
            startDirs: => Seq[File] = startDirs,
            startIvys: => Seq[(String, String, String)] = startIvys,
            jarMap: => File => File = jarMap,
            startResolvers: => Seq[DependencyResolver] = startResolvers,
            startClassLoader: => ClassLoader = startClassLoader,
            startCompilerJars: => Seq[File] = startCompilerJars,
            startCompilerDirs: => Seq[File] = startCompilerDirs,
            startCompilerClassLoader: => ClassLoader = startCompilerClassLoader,
            pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig.copy(lines = 15),
            colors: ColorSet = ColorSet.Default,
            filterUnitResults: Boolean = true): interpreter.Interpreter { def stop(): Unit } = {

    var currentPublish = Option.empty[Publish[Evidence]]
    var currentMessage = Option.empty[ParsedMessage[_]]

    var initialized0 = false

    lazy val underlying = {
      val intp = new Interpreter(
        bridgeConfig = bridgeConfig(
          currentPublish,
          currentMessage,
          startJars = startJars,
          startIvys = startIvys,
          jarMap = jarMap,
          startResolvers = startResolvers,
          pprintConfig = pprintConfig,
          colors = colors
        ),
        wrapper = wrap,
        imports = new ammonite.interpreter.Imports(useClassWrapper = true),
        classes = new Classes(
          startClassLoader,
          (startJars, startDirs),
          startCompilerClassLoader = startCompilerClassLoader,
          startCompilerDeps = (startCompilerJars, startCompilerDirs)
        )
      )
      initialized0 = true
      intp
    }


    new interpreter.Interpreter with InterpreterDefaults {

      def stop() = underlying.stop()

      override def initialized = initialized0
      override def init() = underlying
      def executionCount = underlying.history.length

      override def publish(publish: Publish[ParsedMessage[_]]) = {
        currentPublish = Some(publish.contramap[Evidence](e => e.underlying.asInstanceOf[ParsedMessage[_]]))
      }

      def complete(code: String, pos: Int) = {
        val (pos0, completions, _) = underlying.complete(pos, code)
        (pos0, completions)
      }

      def interpret(line: String,
                    output: Option[(String => Unit, String => Unit)],
                    storeHistory: Boolean,
                    current: Option[ParsedMessage[_]]) = {

        currentMessage = current

        try {
          Parsers.Splitter.parse(line) match {
            case fastparse.core.Result.Failure(_, index) if line.drop(index).trim() == "" =>
              interpreter.Interpreter.Incomplete
            case f: fastparse.core.Result.Failure =>
              interpreter.Interpreter.Error(SyntaxError.msg(f.input, f.parser, f.index))
            case fastparse.core.Result.Success(split, _) =>

              val res = underlying(
                split,
                _(_),
                it => new DisplayData.RawData(
                  it.asInstanceOf[Iterator[String]].mkString
                ),
                stdout = output.map(_._1),
                stderr = output.map(_._2)
              )

              res match {
                case Res.Exit => interpreter.Interpreter.Error("Close this notebook to exit")
                case Res.Failure(reason) => interpreter.Interpreter.Error(reason)
                case Res.Skip => interpreter.Interpreter.NoValue

                case r @ Res.Success(ev) =>
                  underlying.handleOutput(r)
                  interpreter.Interpreter.Value(ev.value)
              }
          }
        }
        finally
          currentMessage = None
      }
    }
  }

}
