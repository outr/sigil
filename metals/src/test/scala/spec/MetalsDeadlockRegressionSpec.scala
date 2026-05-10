package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.metals.MetalsHealthCheck

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #99 — deadlock recovery and duplicate-spawn
 * defenses for `MetalsManager`. The exact failure mode the user hit:
 * a Metals process killed mid-import (SIGKILL / OOM / crash) leaves
 * the embedded H2 db (`<workspace>/.metals/metals.mv.db`) at status
 * `Started`. Every subsequent Metals startup reads it, sees
 * `Started`, logs `skipping build import with status 'Started'`, and
 * sits idle forever. `.bloop/` never lands; every LSP/BSP tool
 * depending on indexing hangs.
 *
 * Three regression cases mirroring the bug spec:
 *   1. Reconcile-stale-status is idempotent and surgical — only
 *      wipes when `hasLivePeer = false`.
 *   2. Concurrent ensureRunning calls collapse onto one spawn
 *      (the placeholder protection from #94 — locks the regression
 *      against a future re-introduction of the duplicate-spawn
 *      window).
 *   3. After a peer dies mid-import, the next ensureRunning recovers
 *      automatically (no manual cleanup, no hang).
 *
 * Cases 2 + 3 use the `fake-metals.sh` fixture so they don't need a
 * real Metals install and run fast. Case 1 doesn't need a process
 * at all.
 */
class MetalsDeadlockRegressionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestMetalsSigil.initFor(getClass.getSimpleName)

  override implicit val testTimeout: FiniteDuration = 60.seconds

  private def newWorkspace(): Path = {
    val p = Files.createTempDirectory(s"metals-deadlock-${rapid.Unique()}-")
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

  /** Write a sentinel `metals.mv.db` to the workspace so the
    * reconcile helper has something to find. Bytes don't matter —
    * the helper deletes the whole file. */
  private def writeSentinelMvDb(workspace: Path): Path = {
    val metals = workspace.resolve(".metals")
    Files.createDirectories(metals)
    val mvDb = metals.resolve("metals.mv.db")
    Files.writeString(mvDb, "stale-import-status-Started\n",
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    mvDb
  }

  "MetalsHealthCheck.reconcileStaleImportStatus (#99 case 1)" should {

    "wipe a stale metals.mv.db when no live peer exists" in {
      val workspace = newWorkspace()
      val mvDb      = writeSentinelMvDb(workspace)
      Files.exists(mvDb) shouldBe true

      MetalsHealthCheck.reconcileStaleImportStatus(workspace, hasLivePeer = false)

      try Files.exists(mvDb) shouldBe false
      finally deleteRecursive(workspace)
    }

    "leave metals.mv.db alone when a live peer is present" in {
      val workspace = newWorkspace()
      val mvDb      = writeSentinelMvDb(workspace)

      MetalsHealthCheck.reconcileStaleImportStatus(workspace, hasLivePeer = true)

      try Files.exists(mvDb) shouldBe true
      finally deleteRecursive(workspace)
    }

    "be a no-op when metals.mv.db doesn't exist (first-ever spawn)" in Task {
      val workspace = newWorkspace()
      try {
        // Idempotent — must not throw.
        MetalsHealthCheck.reconcileStaleImportStatus(workspace, hasLivePeer = false)
        succeed
      } finally deleteRecursive(workspace)
    }
  }

  "MetalsManager.ensureRunning concurrent collapse (#99 case 2)" should {

    "funnel concurrent calls onto a single spawn" in {
      val workspace = newWorkspace()
      val n         = 10
      val parallel: List[Task[String]] = List.fill(n)(
        TestMetalsSigil.metalsManager.ensureRunning(workspace)
      )

      for {
        names  <- Task.sequence(parallel)
        status <- TestMetalsSigil.metalsManager.status
        _      <- TestMetalsSigil.metalsManager.stop(workspace)
        _      <- awaitNoStaleProcess(workspace)
      } yield {
        try {
          names.distinct.size shouldBe 1
          names.size shouldBe n
          status.count(_.workspace == workspace) shouldBe 1
        } finally deleteRecursive(workspace)
      }
    }
  }

  "MetalsManager.ensureRunning recovers from poisoned H2 status (#99 case 3)" should {

    "wipe the stale metals.mv.db on a fresh ensureRunning when no peer is alive" in {
      val workspace = newWorkspace()

      for {
        // Boot once + stop, simulating a prior run.
        _   <- TestMetalsSigil.metalsManager.ensureRunning(workspace)
        _   <- TestMetalsSigil.metalsManager.stop(workspace)
        _   <- awaitNoStaleProcess(workspace)

        // Plant the poisoned-status sentinel as if the prior run
        // had crashed mid-import. Status content doesn't matter —
        // the reconcile path treats any pre-existing mv.db as
        // suspect when no peer is alive.
        mvDb = writeSentinelMvDb(workspace)
        _    = Files.exists(mvDb) shouldBe true

        // Restart: the reconcile helper inside spawnAndResolve
        // must see "no live peer" and wipe the sentinel before
        // launching the new fixture process.
        _ <- TestMetalsSigil.metalsManager.ensureRunning(workspace)

        // After the new spawn, the original sentinel must be gone
        // (the helper wiped it). The fixture writes its own
        // mcp.json under .metals/, so checking specifically that
        // the sentinel CONTENT is gone is the clearest signal —
        // we read it earlier and remember whatever bytes were in
        // it.
        sentinelStillExists =
          if (Files.exists(mvDb)) Files.readString(mvDb).contains("stale-import-status-Started")
          else false

        _ <- TestMetalsSigil.metalsManager.stop(workspace)
        _ <- awaitNoStaleProcess(workspace)
      } yield {
        try {
          sentinelStillExists shouldBe false
        } finally deleteRecursive(workspace)
      }
    }
  }

  "tear down" should {
    "dispose TestMetalsSigil" in TestMetalsSigil.shutdown.map(_ => succeed)
  }
}
