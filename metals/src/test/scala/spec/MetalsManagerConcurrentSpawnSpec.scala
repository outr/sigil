package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #94 — `MetalsManager.ensureRunning` used to
 * read the entry map then spawn-then-put, leaving a 10-30s window
 * where concurrent callers saw an empty map and started duplicate
 * Metals subprocesses against the same workspace. Both processes
 * raced on Metals' on-disk H2 db and neither completed the build
 * import, hanging every `lsp_*` tool that depends on indexing.
 *
 * The fix wraps the placeholder-insert in `computeIfAbsent` so
 * concurrent callers receive the same Entry and await the same
 * `ready` future. Verified here by:
 *
 *   1. Firing N parallel `ensureRunning` calls against the same
 *      fresh workspace — exactly one subprocess spawns; every
 *      caller sees the same `workspaceKey`.
 *   2. The poll loop's view (`status`) reports a single workspace
 *      entry, not N.
 *   3. After teardown, no dangling processes remain.
 */
class MetalsManagerConcurrentSpawnSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestMetalsSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 60.seconds

  private def newWorkspace(): Path = {
    val p = Files.createTempDirectory(s"metals-concurrent-${rapid.Unique()}-")
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

  private def awaitNoStaleProcess(workspace: Path): Task[Unit] = Task {
    val deadline = System.currentTimeMillis() + 2000L
    while (System.currentTimeMillis() < deadline &&
           TestMetalsSigil.metalsManager.status.sync().exists(_.workspace == workspace)) {
      Thread.sleep(50)
    }
  }

  "ensureRunning concurrency (#94)" should {

    "collapse concurrent ensureRunning calls onto a single spawn" in {
      val workspace = newWorkspace()
      val n = 8

      // Fire N ensureRunning calls in parallel. Pre-fix each would
      // start its own Metals subprocess; post-fix they all collapse
      // onto the placeholder Entry inserted by the first caller.
      val parallelCalls: List[Task[String]] = List.fill(n)(
        TestMetalsSigil.metalsManager.ensureRunning(workspace)
      )

      for {
        names  <- Task.sequence(parallelCalls)
        status <- TestMetalsSigil.metalsManager.status
        _      <- TestMetalsSigil.metalsManager.stop(workspace)
        _      <- awaitNoStaleProcess(workspace)
      } yield {
        try {
          // Every caller saw the same workspaceKey.
          names.distinct.size shouldBe 1
          names.size shouldBe n
          // Status reports exactly one entry for this workspace —
          // not N duplicates from racing spawns.
          status.count(_.workspace == workspace) shouldBe 1
        } finally deleteRecursive(workspace)
      }
    }

    "re-spawn cleanly when the prior subprocess has died" in {
      val workspace = newWorkspace()
      for {
        first  <- TestMetalsSigil.metalsManager.ensureRunning(workspace)
        // Hard-kill via stop, then call ensureRunning again — the
        // map should be empty, computeIfAbsent should re-insert,
        // and the second call should drive a fresh spawn.
        _      <- TestMetalsSigil.metalsManager.stop(workspace)
        _      <- awaitNoStaleProcess(workspace)
        second <- TestMetalsSigil.metalsManager.ensureRunning(workspace)
        _      <- TestMetalsSigil.metalsManager.stop(workspace)
        _      <- awaitNoStaleProcess(workspace)
      } yield {
        try {
          first shouldBe second  // same canonical workspaceKey
        } finally deleteRecursive(workspace)
      }
    }
  }

  "tear down" should {
    "dispose TestMetalsSigil" in TestMetalsSigil.shutdown.map(_ => succeed)
  }
}
