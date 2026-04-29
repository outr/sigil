package spec

import fabric.{Json, str}
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, Sigil, SpaceId}
import sigil.conversation.{Conversation, ConversationView, Topic, TopicEntry}
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.event.{Event, MessageRole, MessageVisibility}
import sigil.participant.{AgentParticipantId, ParticipantId, Participant, DefaultAgentParticipant}
import sigil.provider.{ConversationMode, GenerationSettings, Instructions, Provider}
import sigil.signal.Signal
import sigil.tool.core.CoreTools
import sigil.workflow.{JobStepInput, WorkflowCollections, WorkflowHost, WorkflowSigil, WorkflowTemplate}
import sigil.workflow.event.{WorkflowRunCompleted, WorkflowRunStarted, WorkflowStepCompleted}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * End-to-end integration test for the workflow runtime. Builds a
 * minimal [[WorkflowSigil]] over a local DB, schedules a one-step
 * workflow, and asserts the four lifecycle Events flow into the
 * originating conversation's signal stream as the run progresses.
 *
 * Doesn't exercise an LLM provider — the JobStepInput has neither
 * `prompt` nor `tool` set, so [[sigil.workflow.SigilJobStep]] emits
 * `Json.Null` and completes immediately. That's enough to drive
 * `onWorkflowStarted` → `onStepCompleted` → `onWorkflowCompleted`,
 * which is what we're verifying.
 */
class WorkflowEndToEndSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestWorkflowSigil.initFor(getClass.getSimpleName)

  "WorkflowSigil + manager" should {
    "publish all four lifecycle Events into the originating conversation when a workflow runs" in {
      val convId = Conversation.id("workflow-e2e-conv")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      // Subscribe to the unfiltered signal stream so visibility /
      // viewer-filter quirks can't hide lifecycle Events from the
      // assertion. The Events themselves carry visibility = All so
      // both `signals` and `signalsFor(viewer)` see them in
      // production; using `signals` here is just diagnostic clarity.
      TestWorkflowSigil.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)  // give the subscription a moment to attach

      val template = WorkflowTemplate(
        name = "noop",
        description = "Single empty step — completes immediately",
        steps = List(JobStepInput(id = "noop", name = "Noop step")),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(convId)
      )

      // Conversation needs to exist so the manager can resolve
      // participants when publishing lifecycle Events.
      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId],
          modelId = Model.id("test", "model"),
          toolNames = Nil,
          instructions = Instructions(),
          generationSettings = GenerationSettings()
        )),
        currentMode = ConversationMode,
        space = GlobalSpace,
        _id = convId
      )

      for {
        _      <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _      <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        _      <- sigil.workflow.WorkflowScheduler.scheduleTemplate(
                    TestWorkflowSigil, TestWorkflowSigil.workflowDb, template
                  )
        _      <- waitForCompletion(recorded, 10.seconds)
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val all = recorded.iterator().asScala.toList
        val starts = all.collect { case e: WorkflowRunStarted   => e }
        val steps  = all.collect { case e: WorkflowStepCompleted => e }
        val ends   = all.collect { case e: WorkflowRunCompleted  => e }
        starts should have size 1
        steps should have size 1
        steps.head.success shouldBe true
        ends should have size 1
        ends.head.workflowName shouldBe "noop"
      }
    }
  }

  /** Poll the recorded queue until a `WorkflowRunCompleted` shows up
    * (or the timeout fires). Cheaper than a fixed sleep for fast
    * runs and bounded for slow ones. */
  private def waitForCompletion(recorded: ConcurrentLinkedQueue[Signal], timeout: FiniteDuration): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = Task.defer {
      import scala.jdk.CollectionConverters.*
      val seen = recorded.iterator().asScala.exists(_.isInstanceOf[WorkflowRunCompleted])
      if (seen || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    }
    loop
  }
}

case object WorkflowTestUser extends AgentParticipantId {
  override def value: String = "workflow-test-user"
}

object WorkflowTestTopic {
  val id: Id[Topic] = Id("workflow-test-topic")
  val label: String = "Workflow Test"
  val summary: String = "Synthetic topic for the workflow E2E spec."
}

/** Minimal `WorkflowSigil` instance for the integration test —
  * uses Sigil's default DB layout under a per-suite path, no
  * real provider, no participants list except the test user. */
object TestWorkflowSigil extends Sigil with WorkflowSigil {
  override type DB = TestWorkflowDB

  override protected def buildDB(directory: Option[java.nio.file.Path],
                                 storeManager: lightdb.store.CollectionManager,
                                 appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): TestWorkflowDB =
    new TestWorkflowDB(directory, storeManager, appUpgrades)

  override def testMode: Boolean = true

  override protected def participantIds: List[RW[? <: ParticipantId]] =
    List(RW.static(WorkflowTestUser))

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    Task.error(new RuntimeException("TestWorkflowSigil — no provider configured (this spec doesn't drive prompts)"))

  /** Per-suite init: wipes any prior DB at the per-suite path, points
    * `sigil.dbPath` at it, and forces the Sigil instance to start so
    * the workflow manager + Strider engine wire up before tests run. */
  def initFor(testClassName: String): Unit = {
    val name = testClassName.replace("$", "")
    val dbPath = java.nio.file.Path.of("db", "test", name)
    deleteRecursive(dbPath)
    profig.Profig.merge(fabric.obj("sigil" -> fabric.obj("dbPath" -> fabric.str(dbPath.toString))))
    instance.sync()
    ()
  }

  private def deleteRecursive(path: java.nio.file.Path): Unit = {
    if (java.nio.file.Files.exists(path)) {
      import scala.jdk.CollectionConverters.*
      if (java.nio.file.Files.isDirectory(path)) {
        java.nio.file.Files.list(path).iterator().asScala.foreach(deleteRecursive)
      }
      java.nio.file.Files.delete(path)
    }
  }
}

class TestWorkflowDB(directory: Option[java.nio.file.Path],
                     storeManager: lightdb.store.CollectionManager,
                     upgrades: List[lightdb.upgrade.DatabaseUpgrade] = Nil)
  extends SigilDB(directory, storeManager, upgrades)
  with WorkflowCollections
