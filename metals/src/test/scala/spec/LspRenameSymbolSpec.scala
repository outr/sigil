package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.participant.ParticipantId
import sigil.tooling.refactor.{LspRenameSymbolInput, LspRenameSymbolOutput, LspRenameSymbolTool}
import sigil.tooling.{LspManager, LspSession, PermissiveWorkspaceEditApplier, SymbolHit}

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*

/**
 * End-to-end coverage for [[LspRenameSymbolTool]] against a real
 * Metals subprocess. Materializes a minimal sbt workspace with
 * synthetic Scala sources, lets Metals index it, then drives the
 * high-level rename through the tool's `executeTyped` path.
 *
 * Self-skips when the `metals` binary isn't on PATH.
 */
class LspRenameSymbolSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestMetalsSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 10.minutes

  private val metalsBinary: Option[String] = {
    val whichExit = scala.util.Try {
      new ProcessBuilder("which", "metals").redirectErrorStream(true).start().waitFor()
    }.toOption
    if (whichExit.contains(0)) Some("metals")
    else {
      val home = sys.props.getOrElse("user.home", "")
      val candidates = List(
        s"$home/.local/share/coursier/bin/metals",
        s"$home/.local/bin/metals",
        "/usr/local/bin/metals"
      )
      candidates.find(p => Files.isExecutable(Path.of(p)))
    }
  }

  private val skipReason: Option[String] =
    if (metalsBinary.isEmpty) Some("`metals` binary not found (install via coursier: cs install metals)")
    else                      None

  /** Poll `workspace/symbol` until the count stabilises at the
    * requested minimum across two consecutive ticks — Metals returns
    * source-index hits before Bloop has finished compiling, and an
    * lsp `textDocument/rename` against an uncompiled file returns an
    * empty WorkspaceEdit. Waiting for steady state pins the moment
    * SemanticDB is loaded enough to perform real renames. */
  private def pollWorkspaceSymbols(session: LspSession,
                                   query: String,
                                   minMatches: Int,
                                   deadlineMs: Long,
                                   pollMs: Long = 2000L): Task[List[SymbolHit]] = {
    val deadline = System.currentTimeMillis() + deadlineMs
    def loop(prev: Int): Task[List[SymbolHit]] = session.workspaceSymbols(query).flatMap { hits =>
      val now = hits.count(h => h.name.contains(query) || query.contains(h.name))
      if (now >= minMatches && now == prev) Task.pure(hits)
      else if (System.currentTimeMillis() > deadline) Task.pure(hits)
      else Task.sleep(FiniteDuration(pollMs, "millis")).flatMap(_ => loop(now))
    }
    loop(-1)
  }

  private def turnContext(): TurnContext = {
    val convId = Conversation.id(s"rename-symbol-spec-${rapid.Unique()}")
    val conversation = Conversation(
      topics = List(TopicEntry(
        id      = sigil.conversation.Topic.id(s"topic-${rapid.Unique()}"),
        label   = "spec",
        summary = "spec"
      )),
      _id = convId
    )
    TurnContext(
      sigil        = TestMetalsSigil,
      chain        = List(RenameSymbolCallerId(s"caller-${rapid.Unique()}")),
      conversation = conversation,
      turnInput    = TurnInput(conversationId = convId)
    )
  }

  /** Drive a per-scenario flow: materialize workspace, persist
    * Metals LspServerConfig, open a session, wait until indexed,
    * run the tool, run the caller's assertion against the result,
    * then clean up. Assertions run BEFORE workspace cleanup so the
    * caller can read files mutated by the rename. */
  private def runScenario(layout: ScalaWorkspaceLayout,
                          indexProbe: String,
                          minIndexMatches: Int,
                          input: Path => LspRenameSymbolInput)
                         (assertions: (Path, LspRenameSymbolOutput) => org.scalatest.Assertion): Task[org.scalatest.Assertion] = {
    val workspace = ScalaSourceProject.materialize(layout)
    TestMetalsSigil.setLauncher(List(metalsBinary.get))
    val manager = new LspManager(TestMetalsSigil, PermissiveWorkspaceEditApplier)
    val tool = new LspRenameSymbolTool(manager)
    val context = turnContext()

    val flow = for {
      _       <- TestMetalsSigil.writeLspServerConfigForMetals(workspace)
      session <- manager.session("scala", workspace.toAbsolutePath.normalize.toString)
      sources <- Task(ScalaSourceProject.listSources(workspace))
      _       <- Task.sequence(sources.map(p => session.didOpen(p.toUri.toString, "scala", Files.readString(p))))
      _       <- pollWorkspaceSymbols(session, indexProbe, minMatches = minIndexMatches, deadlineMs = 5.minutes.toMillis)
      result  <- tool.invoke(input(workspace), context)
      assert  <- Task(assertions(workspace, result))
    } yield assert

    flow.guarantee {
      manager.shutdownAll().handleError(_ => Task.unit).flatMap { _ =>
        Task {
          TestMetalsSigil.setLauncher(List(
            java.nio.file.Path.of("metals/src/test/resources/fake-metals.sh").toAbsolutePath.normalize.toString
          ))
          ScalaSourceProject.cleanup(workspace)
        }
      }
    }
  }

  "LspRenameSymbolTool" should {

    "rename when exactly one symbol matches the query" in {
      if (skipReason.isDefined) cancel(skipReason.get)
      else runScenario(
        layout          = ScalaWorkspaceLayout.SingleClass("OldFooConfig"),
        indexProbe      = "OldFooConfig",
        minIndexMatches = 1,
        input = root => LspRenameSymbolInput(
          languageId  = "scala",
          projectRoot = root.toAbsolutePath.normalize.toString,
          symbolName  = "OldFooConfig",
          newName     = "FooConfig"
        )
      ) { (workspace, output) =>
        output match {
          case r: LspRenameSymbolOutput.Renamed =>
            r.symbolName shouldBe "OldFooConfig"
            r.newName    shouldBe "FooConfig"
            r.filesChanged should be > 0
            val oldPath = workspace.resolve("src/main/scala/OldFooConfig.scala")
            val newPath = workspace.resolve("src/main/scala/FooConfig.scala")
            (Files.exists(oldPath), Files.exists(newPath)) match {
              case (false, true) =>
                Files.readString(newPath) should include("class FooConfig")
              case (true, _) =>
                Files.readString(oldPath) should include("class FooConfig")
              case other => fail(s"neither expected source file resolved: $other")
            }
          case other => fail(s"expected Renamed, got $other")
        }
      }
    }

    "return NotFound when no symbol matches the query" in {
      if (skipReason.isDefined) cancel(skipReason.get)
      else runScenario(
        layout          = ScalaWorkspaceLayout.SingleClass("OldFooConfig"),
        indexProbe      = "OldFooConfig",
        minIndexMatches = 1,
        input = root => LspRenameSymbolInput(
          languageId  = "scala",
          projectRoot = root.toAbsolutePath.normalize.toString,
          symbolName  = "DoesNotExist",
          newName     = "Whatever"
        )
      ) { (_, output) =>
        output match {
          case nf: LspRenameSymbolOutput.NotFound =>
            nf.symbolName shouldBe "DoesNotExist"
            nf.reason.toLowerCase should include("no symbol")
          case other => fail(s"expected NotFound, got $other")
        }
      }
    }

    "return Ambiguous when multiple symbols share the name and no kindHint disambiguates" in {
      if (skipReason.isDefined) cancel(skipReason.get)
      else runScenario(
        layout          = ScalaWorkspaceLayout.ClassAndMethod("AmbiguousFoo"),
        indexProbe      = "AmbiguousFoo",
        minIndexMatches = 2,
        input = root => LspRenameSymbolInput(
          languageId  = "scala",
          projectRoot = root.toAbsolutePath.normalize.toString,
          symbolName  = "AmbiguousFoo",
          newName     = "DisambiguatedFoo"
        )
      ) { (_, output) =>
        output match {
          case a: LspRenameSymbolOutput.Ambiguous =>
            a.symbolName shouldBe "AmbiguousFoo"
            a.matches.size should be >= 2
            a.matches.map(_.kind).toSet should contain allOf ("class", "method")
          case other => fail(s"expected Ambiguous, got $other")
        }
      }
    }

    "resolve to the class when multiple symbols share the name and kindHint='class' is supplied" in {
      if (skipReason.isDefined) cancel(skipReason.get)
      else runScenario(
        layout          = ScalaWorkspaceLayout.ClassAndMethod("AmbiguousFoo"),
        indexProbe      = "AmbiguousFoo",
        minIndexMatches = 2,
        input = root => LspRenameSymbolInput(
          languageId  = "scala",
          projectRoot = root.toAbsolutePath.normalize.toString,
          symbolName  = "AmbiguousFoo",
          newName     = "DisambiguatedFoo",
          kindHint    = Some("class")
        )
      ) { (workspace, output) =>
        output match {
          case r: LspRenameSymbolOutput.Renamed =>
            r.symbolName shouldBe "AmbiguousFoo"
            r.newName    shouldBe "DisambiguatedFoo"
            r.filesChanged should be > 0
            val classFile = workspace.resolve("src/main/scala/AmbiguousFoo.scala")
            val renamedClassFile = workspace.resolve("src/main/scala/DisambiguatedFoo.scala")
            val classContent =
              if (Files.exists(renamedClassFile)) Files.readString(renamedClassFile)
              else                                Files.readString(classFile)
            classContent should include("class DisambiguatedFoo")
            val containerFile = workspace.resolve("src/main/scala/Container.scala")
            Files.readString(containerFile) should include("def AmbiguousFoo")
          case other => fail(s"expected Renamed, got $other")
        }
      }
    }
  }

  "tear down" should {
    "dispose TestMetalsSigil" in TestMetalsSigil.shutdown.map(_ => succeed)
  }
}

private case class RenameSymbolCallerId(value: String) extends ParticipantId

/** Per-scenario workspace shape for [[LspRenameSymbolSpec]]. */
private sealed trait ScalaWorkspaceLayout
private object ScalaWorkspaceLayout {
  case class SingleClass(name: String) extends ScalaWorkspaceLayout
  case class ClassAndMethod(name: String) extends ScalaWorkspaceLayout
}

/** Materialises a minimal sbt workspace populated with synthetic
  * Scala sources matching the requested [[ScalaWorkspaceLayout]]. */
private object ScalaSourceProject {
  def materialize(layout: ScalaWorkspaceLayout): Path = {
    val root = Files.createTempDirectory(s"rename-symbol-${rapid.Unique()}-").toAbsolutePath.normalize
    write(root.resolve("build.sbt"),
      """ThisBuild / scalaVersion := "2.13.14"
        |ThisBuild / organization := "spec"
        |
        |lazy val root = (project in file("."))
        |  .settings(name := "rename-symbol-fixture")
        |""".stripMargin
    )
    val projectDir = Files.createDirectories(root.resolve("project"))
    write(projectDir.resolve("build.properties"), "sbt.version=1.10.5\n")
    val srcDir = Files.createDirectories(root.resolve("src/main/scala"))
    layout match {
      case ScalaWorkspaceLayout.SingleClass(name) =>
        write(srcDir.resolve(s"$name.scala"),
          s"""class $name(val value: Int) {
             |  def doubled: Int = value * 2
             |}
             |""".stripMargin
        )
      case ScalaWorkspaceLayout.ClassAndMethod(name) =>
        write(srcDir.resolve(s"$name.scala"),
          s"""class $name(val payload: String)
             |""".stripMargin
        )
        write(srcDir.resolve("Container.scala"),
          s"""object Container {
             |  def $name(): Unit = ()
             |}
             |""".stripMargin
        )
    }
    root
  }

  /** Every Scala source file Metals should didOpen before query. */
  def listSources(root: Path): List[Path] = {
    val src = root.resolve("src/main/scala")
    if (!Files.exists(src)) Nil
    else {
      val s = Files.walk(src)
      try {
        import scala.jdk.CollectionConverters.*
        s.iterator().asScala.toList.filter(p =>
          Files.isRegularFile(p) && p.getFileName.toString.endsWith(".scala")
        )
      } finally s.close()
    }
  }

  def cleanup(root: Path): Unit = {
    if (Files.exists(root)) {
      import scala.jdk.CollectionConverters.*
      val s = Files.walk(root)
      try s.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()
    }
  }

  private def write(path: Path, content: String): Unit = {
    Files.writeString(path, content,
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    ()
  }
}
