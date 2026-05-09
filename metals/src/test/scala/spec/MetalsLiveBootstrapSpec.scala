package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.tooling.{LspManager, LspServerConfig}

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

    /** Sigil bug #93 reproducer in real-Metals shape. After
      * [[sigil.metals.StartMetalsTool]] writes the LspServerConfig
      * (bug #88), an `lsp_*` tool call goes through
      * [[LspManager.session]] which spawns a fresh Metals
      * subprocess and constructs an lsp4j launcher around an
      * [[sigil.tooling.LspRecordingClient]]. Pre-#93 the launcher
      * threw `Duplicate RPC method workspace/configuration` at
      * construction. This spec exercises the whole chain against
      * a real Metals binary. */
    "drive an lsp4j launcher against real Metals via LspManager (bug #93)" in {
      if (skipReason.isDefined) cancel(skipReason.get)
      else {
        val workspace = MetalsHelloWorldProject.materialize()
        TestMetalsSigil.setLauncher(List("metals"))
        val manager = new LspManager(TestMetalsSigil)

        TestMetalsSigil.metalsManager.ensureRunning(workspace).flatMap { _ =>
          // Bug #88 — the same auto-write StartMetalsTool performs
          // when an agent calls `start_metals`.
          TestMetalsSigil.writeLspServerConfigForMetals(workspace).flatMap { _ =>
            TestMetalsSigil.withDB(_.lspServers.transaction(_.get(LspServerConfig.idFor("scala")))).flatMap { persisted =>
              // Bug #93 — drive `LspManager.session` against the
              // persisted config. This is what `lsp_workspace_symbols`
              // does internally; pre-fix the launcher construction
              // threw before the LSP handshake even started.
              manager.session("scala", workspace.toAbsolutePath.normalize.toString).map { session =>
                persisted.map(_.languageId) shouldBe Some("scala")
                session should not be null
              }
            }
          }
        }.map { result =>
          try result
          finally {
            manager.shutdownAll().sync()
            TestMetalsSigil.metalsManager.stop(workspace).sync()
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
