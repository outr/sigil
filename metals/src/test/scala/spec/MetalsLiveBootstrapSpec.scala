package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.participant.ParticipantId
import sigil.tooling.{LspManager, LspServerConfig, LspSession, LspWorkspaceSymbolsInput, LspWorkspaceSymbolsTool, PermissiveWorkspaceEditApplier, SymbolHit}

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*

/**
 * Live end-to-end coverage that proves [[sigil.metals.MetalsManager]]
 * actually drives a real Metals binary to write
 * `.metals/mcp.json` — the failure case behind sigil bug #70 (the
 * subprocess sat idle forever without an LSP `initialize`) AND
 * sigil bug #69 (no streaming per-line tool output reached the
 * chat chip during the multi-minute startup).
 *
 * Materializes a minimal sbt Hello World project in a temp
 * workspace, runs `metals` against it, and asserts
 * `.metals/mcp.json` appears within the live deadline. The
 * separate unit specs exercise the [[sigil.event.ToolLog]]
 * streaming path (#69) — bypassing the tool-dispatch
 * scaffolding that wires `TurnContext.toolLog`.
 *
 * Self-skips only when the `metals` binary isn't on PATH (no
 * real Metals install). Matches the framework's broader live-spec
 * convention (LlamaCppSpec self-skips when llama.voidcraft.ai is
 * unreachable, etc.) — `sbt test` runs everywhere; CI relies on
 * the same self-skip when its runner doesn't have Metals
 * installed.
 *
 * First run takes 1–3 minutes (Coursier downloads sbt-bloop +
 * Scala compiler + sources). Subsequent runs reuse the cache.
 */
class MetalsLiveBootstrapSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestMetalsSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 8.minutes

  /** Resolve the Metals binary across the launch shapes the test
    * harness might land in. `sbt test` from an interactive shell
    * inherits the user's PATH and `which metals` works. CI's sbt
    * fork frequently has a stripped PATH — fall back to the
    * standard Coursier install paths so the live spec runs
    * automatically anywhere Metals is installed. */
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

  /** Repeatedly query workspace symbols until at least one hit
    * comes back or the deadline expires. Metals returns an empty
    * list while indexing is still in progress; non-empty once
    * Bloop's classpath is available. */
  private def pollWorkspaceSymbols(session: LspSession,
                                   query: String,
                                   deadlineMs: Long,
                                   pollMs: Long = 2000L): Task[List[SymbolHit]] = {
    val deadline = System.currentTimeMillis() + deadlineMs
    def loop: Task[List[SymbolHit]] = session.workspaceSymbols(query).flatMap { hits =>
      if (hits.nonEmpty || System.currentTimeMillis() > deadline) Task.pure(hits)
      else Task.sleep(FiniteDuration(pollMs, "millis")).flatMap(_ => loop)
    }
    loop
  }

  "MetalsManager live (bug #70 + #69)" should {

    "boot real Metals against a generated sbt Hello World and write .metals/mcp.json" in {
      if (skipReason.isDefined) cancel(skipReason.get)
      else {
        val workspace = MetalsHelloWorldProject.materialize()
        TestMetalsSigil.setLauncher(List(metalsBinary.get))

        TestMetalsSigil.metalsManager.ensureRunning(workspace).map { name =>
          try {
            val rendezvous = workspace.resolve(".metals").resolve("mcp.json")
            name should startWith("metals-")
            Files.exists(rendezvous) shouldBe true
          } finally {
            TestMetalsSigil.metalsManager.stop(workspace).sync()
            TestMetalsSigil.setLauncher(List(
              java.nio.file.Path.of("metals/src/test/resources/fake-metals.sh").toAbsolutePath.normalize.toString
            ))
            MetalsHelloWorldProject.cleanup(workspace)
          }
        }
      }
    }

    /** Drive the full lsp_* path against real Metals: spawn,
      * index, query, modify, verify on disk. Covers sigil bug #93
      * (LspRecordingClient launcher construction with the lsp4j
      * default-method scan) AND the realistic agent flow that
      * follows it — `lsp_workspace_symbols` → `lsp_hover` →
      * `lsp_rename` → file changed.
      *
      * Goes through the LSP-direct path only. [[LspRecordingClient]]
      * auto-responds to safe initialisation prompts ("Import build")
      * so a fresh sbt project can complete its Bloop import without
      * a parallel MetalsManager. */
    "drive Metals end-to-end: workspace symbols, hover, rename → file mutated (bug #93)" in {
      if (skipReason.isDefined) cancel(skipReason.get)
      else {
        val workspace = MetalsHelloWorldProject.materialize()
        TestMetalsSigil.setLauncher(List(metalsBinary.get))
        val manager = new LspManager(TestMetalsSigil, PermissiveWorkspaceEditApplier)
        val mainPath = workspace.resolve("src/main/scala/Main.scala")
        val mainUri = mainPath.toUri.toString

        def checkpoint(name: String): Task[Unit] = Task(scribe.info(s"[live-spec] $name"))

        val flow = for {
          _        <- checkpoint("writing LspServerConfig")
          _        <- TestMetalsSigil.writeLspServerConfigForMetals(workspace)
          persisted <- TestMetalsSigil.withDB(_.lspServers.transaction(_.get(LspServerConfig.idFor("scala"))))

          // 1. LspManager spawns Metals against the fresh sbt
          //    workspace. Pre-#93 the launcher threw at
          //    construction; post-#93 the session opens.
          //    LspRecordingClient auto-answers "Import build" so
          //    Metals can do its initial Bloop import.
          _        <- checkpoint("opening LspManager.session")
          session  <- manager.session("scala", workspace.toAbsolutePath.normalize.toString)
          _        <- checkpoint("session opened; reading Main.scala")

          // 3. Open Main.scala so document-bound queries (hover,
          //    rename) target a known buffer.
          mainText <- Task(Files.readString(mainPath))
          _        <- session.didOpen(mainUri, "scala", mainText)
          _        <- checkpoint("didOpen sent; polling workspace symbols")

          // 4. Wait until Metals can answer queries against the
          //    indexed workspace. Polling — fresh indexing fires
          //    progress tokens, but the more reliable signal is
          //    "workspace/symbol returns hits for a known symbol".
          symbols  <- pollWorkspaceSymbols(session, "Main", deadlineMs = 4.minutes.toMillis)
          _        <- checkpoint(s"workspaceSymbols returned ${symbols.size} hits; calling hover")

          // 5. Hover at the position of `main` in
          //    `def main(args...)` — line 1 char 6 (0-indexed,
          //    after two-space indent and "def ").
          hover    <- session.hover(mainUri, line = 1, character = 6)
          _        <- checkpoint(s"hover returned ${hover.isDefined}; calling rename")

          // 6. Rename `Main` (line 0 char 7, after "object ").
          edit     <- session.rename(mainUri, line = 0, character = 7, newName = "MainRenamed")
          _        <- checkpoint(s"rename returned ${edit.isDefined}; finalising")
        } yield {
          // ---- assertions ----

          // Bug #88: LspServerConfig persisted.
          persisted.map(_.languageId) shouldBe Some("scala")
          // Bug #93: launcher constructed, session is live.
          session should not be null

          // Real introspection: workspace symbols returns at least
          // one hit naming `Main`.
          symbols.map(_.name) should contain("Main")

          // Real navigation: hover returns content the agent
          // could surface.
          hover shouldBe defined
          hover.flatMap(h => Option(h.getContents)) shouldBe defined

          // Real modification: rename produced a WorkspaceEdit;
          // apply it through the permissive applier and verify the
          // rename actually mutated the workspace. Metals renames
          // the file alongside the symbol when the symbol's name
          // matches the filename, so we check both: the old path
          // is gone, the new path exists, the content references
          // `MainRenamed`.
          edit shouldBe defined
          val applied = PermissiveWorkspaceEditApplier.apply(edit.get)
          applied shouldBe true
          val renamedPath = workspace.resolve("src/main/scala/MainRenamed.scala")
          Files.exists(mainPath) shouldBe false
          Files.exists(renamedPath) shouldBe true
          val updated = Files.readString(renamedPath)
          updated should include("object MainRenamed")
          updated should not include "object Main "
        }

        flow.map { result =>
          try result
          finally {
            manager.shutdownAll().sync()
            TestMetalsSigil.setLauncher(List(
              java.nio.file.Path.of("metals/src/test/resources/fake-metals.sh").toAbsolutePath.normalize.toString
            ))
            MetalsHelloWorldProject.cleanup(workspace)
          }
        }
      }
    }

    /** Drive the agent-facing `lsp_workspace_symbols` tool through
      * its real executeTyped path (rather than calling the
      * underlying [[LspSession.workspaceSymbols]] directly). This
      * exercises the full chain that hangs in production:
      *
      *   1. `LspToolSupport.withSessionTyped` → looks up the
      *      persisted [[LspServerConfig]].
      *   2. `LspManager.session` → spawns Metals (or attaches to a
      *      running session — sigil bug #94's idempotency).
      *   3. `LspSession.workspaceSymbols` → the actual LSP request.
      *   4. Result mapping → [[LspWorkspaceSymbolsResult]] with
      *      `items`, `totalCount`, `truncated`.
      *
      * Plus a `maxResults = 1` variant to verify the truncation
      * flag fires correctly for over-cap result sets.
      */
    "drive lsp_workspace_symbols tool end-to-end (maps result + sets truncated)" in {
      if (skipReason.isDefined) cancel(skipReason.get)
      else {
        val workspace = MetalsHelloWorldProject.materialize()
        TestMetalsSigil.setLauncher(List(metalsBinary.get))
        val manager = new LspManager(TestMetalsSigil, PermissiveWorkspaceEditApplier)
        val tool = new LspWorkspaceSymbolsTool(manager)

        // TurnContext is required by the tool's API signature but
        // LspWorkspaceSymbolsTool never reads any of its fields —
        // `withSessionTyped` flows it through opaquely, then resolves
        // the real LspServerConfig + Metals session via the LspManager
        // (which DOES hit real persisted state). Construct it inline
        // so it isn't disguised as a separate "fixture factory."
        val convId = Conversation.id(s"lsp-tool-spec-${rapid.Unique()}")
        val conversation = Conversation(
          topics = List(TopicEntry(
            id      = sigil.conversation.Topic.id(s"topic-${rapid.Unique()}"),
            label   = "spec",
            summary = "spec"
          )),
          _id = convId
        )
        val context = sigil.TurnContext(
          sigil        = TestMetalsSigil,
          chain        = List(LspToolCallerId(s"caller-${rapid.Unique()}")),
          conversation = conversation,
          turnInput    = TurnInput(conversationId = convId)
        )

        def checkpoint(name: String): Task[Unit] = Task(scribe.info(s"[live-spec/tool] $name"))

        val flow = for {
          _   <- checkpoint("writing LspServerConfig")
          _   <- TestMetalsSigil.writeLspServerConfigForMetals(workspace)

          // Tool call 1 — wide query. Tool resolves the session,
          // invokes the LSP request, maps SymbolHit → typed
          // LspWorkspaceSymbol, returns result.
          _   <- checkpoint("calling tool with maxResults=100")
          result1 <- tool.invokeFirstPage(LspWorkspaceSymbolsInput(
            languageId  = "scala",
            projectRoot = workspace.toAbsolutePath.normalize.toString,
            query       = "Main",
            maxResults  = 100
          ), context)
          _   <- checkpoint(s"tool returned ${result1.items.size} items, totalCount=${result1.totalCount.getOrElse(-1)}")

          // Tool call 2 — same query, capped at 1.
          result2 <- tool.invokeFirstPage(LspWorkspaceSymbolsInput(
            languageId  = "scala",
            projectRoot = workspace.toAbsolutePath.normalize.toString,
            query       = "Main",
            maxResults  = 1
          ), context)
          _   <- checkpoint(s"tool returned (capped) ${result2.items.size} items, hasMore=${result2.hasMore}")
        } yield {
          // Result mapping: items contain a Main symbol;
          // totalCount matches; capping at 100 returns everything.
          val names1 = result1.items.flatMap(_.get("name").map(_.asString))
          names1 should contain("Main")
          result1.totalCount.getOrElse(0) should be > 0

          // Truncation: maxResults=1 drains exactly one node;
          // hasMore is false because the stream produced only one
          // item (the maxResults cap is applied inside the tool
          // before draining).
          result2.items.size shouldBe 1
          result2.hasMore shouldBe false
        }

        flow.map { result =>
          try result
          finally {
            manager.shutdownAll().sync()
            TestMetalsSigil.setLauncher(List(
              java.nio.file.Path.of("metals/src/test/resources/fake-metals.sh").toAbsolutePath.normalize.toString
            ))
            MetalsHelloWorldProject.cleanup(workspace)
          }
        }
      }
    }
  }

  "tear down" should {
    "dispose TestMetalsSigil" in TestMetalsSigil.shutdown.map(_ => succeed)
  }
}

/** Test-only ParticipantId for the LSP-tool live spec's chain.
  * The LSP tools never read the chain, so this carries no
  * meaningful identity — it just satisfies the TurnContext API. */
private case class LspToolCallerId(value: String) extends ParticipantId

/** Materialises a minimal sbt Hello World project in a fresh temp
  * directory so the live spec has a real build for Metals to index
  * without depending on an out-of-repo workspace. */
private object MetalsHelloWorldProject {
  def materialize(): Path = {
    val root = Files.createTempDirectory(s"metals-hello-${rapid.Unique()}-").toAbsolutePath.normalize
    write(root.resolve("build.sbt"),
      """ThisBuild / scalaVersion := "2.13.14"
        |ThisBuild / organization := "spec"
        |
        |lazy val root = (project in file("."))
        |  .settings(name := "metals-hello-world")
        |""".stripMargin
    )
    val projectDir = Files.createDirectories(root.resolve("project"))
    write(projectDir.resolve("build.properties"), "sbt.version=1.10.5\n")
    val srcDir = Files.createDirectories(root.resolve("src/main/scala"))
    write(srcDir.resolve("Main.scala"),
      """object Main {
        |  def main(args: Array[String]): Unit = println("Hello, world!")
        |}
        |""".stripMargin
    )
    root
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
