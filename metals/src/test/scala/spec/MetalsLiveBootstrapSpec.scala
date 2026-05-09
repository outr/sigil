package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.tooling.{LspManager, LspServerConfig, LspSession, PermissiveWorkspaceEditApplier, SymbolHit}

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

  private val metalsOnPath: Boolean = {
    val pb = new ProcessBuilder("which", "metals").redirectErrorStream(true)
    scala.util.Try(pb.start().waitFor()).toOption.contains(0)
  }

  private val skipReason: Option[String] =
    if (!metalsOnPath) Some("`metals` binary not on PATH (install via coursier: cs install metals)")
    else               None

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
        TestMetalsSigil.setLauncher(List("metals"))

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
        TestMetalsSigil.setLauncher(List("metals"))
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
  }

  "tear down" should {
    "dispose TestMetalsSigil" in TestMetalsSigil.shutdown.map(_ => succeed)
  }
}

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
