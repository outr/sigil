package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.mcp.{McpServerConfig, McpTransport}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * End-to-end coverage of [[sigil.metals.MetalsManager]] — drives the
 * spawn / rendezvous / port-update / idle-reap / shutdown flow via
 * the `fake-metals.sh` fixture (see `src/test/resources`). The
 * fixture writes a real `.metals/mcp.json` and stays alive until
 * killed, exactly like Metals would.
 *
 * Per-test workspaces live under `target/metals-spec-<unique>/` so
 * concurrent suites don't trip over each other's `.metals/` dirs.
 */
class MetalsManagerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestMetalsSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 60.seconds

  private def newWorkspace(): Path = {
    val p = Files.createTempDirectory(s"metals-spec-${rapid.Unique()}-")
    p.toAbsolutePath.normalize
  }

  private def deleteRecursive(p: Path): Unit = {
    if (Files.exists(p)) {
      import scala.jdk.CollectionConverters.*
      val s = Files.walk(p)
      try s.iterator().asScala.toList.reverse.foreach(x => Files.deleteIfExists(x))
      finally s.close()
    }
  }

  /** Wait for the fake-metals process tree (the bash script + its
    * sleep child) to actually exit after teardown. The
    * `Process.destroy()` SIGTERM lands fast but the bash subshell
    * needs a moment to settle; bounded poll with a 2s ceiling. */
  private def awaitNoStaleProcess(workspace: Path): Task[Unit] = Task {
    val deadline = System.currentTimeMillis() + 2000L
    while (System.currentTimeMillis() < deadline &&
           TestMetalsSigil.metalsManager.status.sync().exists(_.workspace == workspace)) {
      Thread.sleep(50)
    }
  }

  "MetalsManager.ensureRunning" should {

    "spawn the fixture, detect the rendezvous file, and register an McpServerConfig" in {
      val workspace = newWorkspace()
      TestMetalsSigil.setWorkspace(Some(workspace))
      for {
        name <- TestMetalsSigil.metalsManager.ensureRunning(workspace)
        cfgs <- TestMetalsSigil.mcpManager.listConfigs()
        _    <- TestMetalsSigil.metalsManager.stop(workspace)
        _    <- awaitNoStaleProcess(workspace)
      } yield {
        try {
          name should startWith("metals-")
          val ours = cfgs.find(_.name == name).getOrElse(fail(s"No McpServerConfig registered for $name"))
          ours.transport shouldBe a [McpTransport.HttpSse]
          ours.transport.asInstanceOf[McpTransport.HttpSse].url.toString should include("54321")
          ours.roots should contain(workspace.toString)
        } finally deleteRecursive(workspace)
      }
    }

    "be idempotent — second call against the same workspace doesn't respawn" in {
      val workspace = newWorkspace()
      for {
        name1 <- TestMetalsSigil.metalsManager.ensureRunning(workspace)
        name2 <- TestMetalsSigil.metalsManager.ensureRunning(workspace)
        _     <- TestMetalsSigil.metalsManager.stop(workspace)
        _     <- awaitNoStaleProcess(workspace)
      } yield {
        try {
          name1 shouldBe name2
        } finally deleteRecursive(workspace)
      }
    }

    "report the running workspace via status with the resolved endpoint" in {
      val workspace = newWorkspace()
      for {
        _      <- TestMetalsSigil.metalsManager.ensureRunning(workspace)
        status <- TestMetalsSigil.metalsManager.status
        _      <- TestMetalsSigil.metalsManager.stop(workspace)
        _      <- awaitNoStaleProcess(workspace)
      } yield {
        try {
          val ours = status.find(_.workspace == workspace).getOrElse(
            fail(s"No status entry for $workspace; saw ${status.map(_.workspace).mkString(", ")}")
          )
          ours.alive shouldBe true
          ours.endpoint shouldBe defined
          ours.endpoint.get should include("54321")
        } finally deleteRecursive(workspace)
      }
    }
  }

  "MetalsManager.stop" should {
    "tear the subprocess down and remove its McpServerConfig row" in {
      val workspace = newWorkspace()
      for {
        name      <- TestMetalsSigil.metalsManager.ensureRunning(workspace)
        cfgsBefore <- TestMetalsSigil.mcpManager.listConfigs()
        stopped   <- TestMetalsSigil.metalsManager.stop(workspace)
        _         <- awaitNoStaleProcess(workspace)
        // Give the McpManager a tick to finish removeConfig.
        _         <- Task.sleep(100.millis)
        cfgsAfter <- TestMetalsSigil.mcpManager.listConfigs()
      } yield {
        try {
          stopped shouldBe true
          cfgsBefore.exists(_.name == name) shouldBe true
          cfgsAfter.exists(_.name == name) shouldBe false
        } finally deleteRecursive(workspace)
      }
    }

    "return false when nothing is running for the workspace" in {
      val workspace = newWorkspace()
      TestMetalsSigil.metalsManager.stop(workspace).map { stopped =>
        try stopped shouldBe false finally deleteRecursive(workspace)
      }
    }
  }

  "MetalsManager port-change handling (rendezvous drift)" should {
    "detect a port change in .metals/mcp.json and force-reconnect via McpManager.closeClient" in {
      val workspace = newWorkspace()
      for {
        name <- TestMetalsSigil.metalsManager.ensureRunning(workspace)
        // Simulate Metals restarting on a different port: rewrite
        // the rendezvous file in place. The watcher's poll loop
        // picks the change up within `PollIntervalMs * a few`.
        _     = Files.writeString(workspace.resolve(".metals").resolve("mcp.json"),
                                  """{"port": 65111}""")
        _    <- Task.sleep(800.millis)  // poll interval is 200ms
        cfgs <- TestMetalsSigil.mcpManager.listConfigs()
        _    <- TestMetalsSigil.metalsManager.stop(workspace)
        _    <- awaitNoStaleProcess(workspace)
      } yield {
        try {
          val ours = cfgs.find(_.name == name).getOrElse(
            fail(s"$name disappeared after port change; saw ${cfgs.map(_.name).mkString(", ")}")
          )
          ours.transport.asInstanceOf[McpTransport.HttpSse].url.toString should include("65111")
        } finally deleteRecursive(workspace)
      }
    }
  }

  "MetalsManager.shutdown" should {
    "tear down every spawned subprocess" in {
      val w1 = newWorkspace()
      val w2 = newWorkspace()
      for {
        _    <- TestMetalsSigil.metalsManager.ensureRunning(w1)
        _    <- TestMetalsSigil.metalsManager.ensureRunning(w2)
        before <- TestMetalsSigil.metalsManager.status
        _    <- TestMetalsSigil.metalsManager.shutdown
        after <- TestMetalsSigil.metalsManager.status
      } yield {
        try {
          before.size should be >= 2
          after shouldBe empty
        } finally {
          deleteRecursive(w1); deleteRecursive(w2)
        }
      }
    }
  }

  "tear down" should {
    "dispose TestMetalsSigil" in TestMetalsSigil.shutdown.map(_ => succeed)
  }
}
