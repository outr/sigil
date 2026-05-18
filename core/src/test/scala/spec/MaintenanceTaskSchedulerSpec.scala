package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.maintenance.MaintenanceTask

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Coverage for the per-task fiber scheduler that drives every
 * [[MaintenanceTask]] registered via [[Sigil.maintenanceTasks]]
 * (Bug #9 phase 1).
 *
 * One Sigil instance, two registered tasks (one counting, one
 * failing). Asserts:
 *   - With `runImmediatelyOnStart = true` the counting task fires
 *     at least twice in 400 ms at 100 ms cadence (one immediate +
 *     several ticks).
 *   - The failing task's exception is contained — the counting task
 *     keeps ticking despite its sibling throwing every run.
 *   - Both tasks attempt every tick; failure doesn't drop a task
 *     from the schedule.
 */
class MaintenanceTaskSchedulerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private val healthy = new AtomicInteger(0)
  private val broken = new AtomicInteger(0)

  /**
   * Counter-based maintenance task — bumps on every `runOnce`.
   */
  private case class CountingTask(name: String,
                                  interval: FiniteDuration,
                                  counter: AtomicInteger)
    extends MaintenanceTask {
    override def runOnce(host: Sigil): Task[Unit] = Task { counter.incrementAndGet(); () }
  }

  /**
   * Maintenance task that always throws. Used to verify failure
   * isolation — sibling tasks keep ticking even if this one is
   * broken.
   */
  private case class FailingTask(name: String,
                                 interval: FiniteDuration,
                                 attempts: AtomicInteger)
    extends MaintenanceTask {
    override def runOnce(host: Sigil): Task[Unit] =
      Task { attempts.incrementAndGet(); () }.flatMap(_ => Task.error(new RuntimeException("intentional")))
  }

  MaintenanceTaskTestSigil.setTasks(List(
    CountingTask("healthy", 100.millis, healthy),
    FailingTask("broken", 100.millis, broken)
  ))
  MaintenanceTaskTestSigil.initFor(getClass.getSimpleName)

  "Sigil.maintenanceTasks scheduler" should {
    "fire registered tasks on their cadence and isolate failures" in
      Task.sleep(400.millis).map { _ =>
        // Immediate first tick + ~3 more in 400ms at 100ms cadence.
        healthy.get() should be >= 2
        // Broken task still attempts every tick — failure doesn't
        // drop it from the schedule.
        broken.get() should be >= 2
      }
  }

  "tear down" should {
    "dispose MaintenanceTaskTestSigil" in MaintenanceTaskTestSigil.shutdown.map(_ => succeed)
  }
}

/**
 * Per-suite Sigil exposing [[Sigil.maintenanceTasks]] via a mutable
 * hook so the spec can register its tasks before `initFor` boots
 * the instance. Mirrors [[TestSigil]]'s minimal shape; no provider,
 * no participants.
 */
object MaintenanceTaskTestSigil extends sigil.Sigil {
  override type DB = sigil.db.DefaultSigilDB
  override protected def buildDB(directory: Option[java.nio.file.Path],
                                 storeManager: lightdb.store.CollectionManager,
                                 appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
    new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)
  override def testMode: Boolean = true

  private val tasksRef = new java.util.concurrent.atomic.AtomicReference[List[MaintenanceTask]](Nil)
  def setTasks(tasks: List[MaintenanceTask]): Unit = tasksRef.set(tasks)

  override def maintenanceTasks: List[MaintenanceTask] = tasksRef.get()

  override def providerFor(modelId: lightdb.id.Id[sigil.db.Model],
                           chain: List[sigil.participant.ParticipantId]): rapid.Task[sigil.provider.Provider] =
    Task.error(new RuntimeException("no provider"))

  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit =
    if (java.nio.file.Files.exists(path)) {
      val stream = java.nio.file.Files.walk(path)
      try {
        import scala.jdk.CollectionConverters.*
        stream.iterator().asScala.toList.reverse.foreach(p => java.nio.file.Files.deleteIfExists(p))
      } finally stream.close()
    }
}
