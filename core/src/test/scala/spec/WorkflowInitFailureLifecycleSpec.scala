package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.GlobalSpace
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.db.Model
import sigil.participant.{AgentParticipantId, DefaultAgentParticipant}
import sigil.provider.{ConversationMode, GenerationSettings, Instructions}
import sigil.signal.Signal
import sigil.workflow.{JobStepInput, WorkflowTemplate}
import sigil.workflow.event.{WorkflowRunCompleted, WorkflowRunFailed, WorkflowRunStarted}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Coverage for the invariant that every workflow run terminates with a
 * lifecycle signal consumers can observe — `WorkflowRunStarted` →
 * (`WorkflowRunCompleted` | `WorkflowRunFailed`).
 *
 * A first-step exception (model resolution miss, missing host dep, etc.)
 * — `WorkflowRunStarted` AND `WorkflowRunFailed` must still publish, so
 * downstream UIs render a "failed" chip rather than a frozen "still
 * running" placeholder. The lifecycle events land on the run's
 * `conversationId` (or, for a participant-less conversation, its
 * parent's first participant).
 */
class WorkflowInitFailureLifecycleSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestWorkflowSigil.initFor(getClass.getSimpleName)

  "WorkflowSigil" should {

    "publish WorkflowRunStarted then WorkflowRunFailed when the first step throws (worker-shaped conv with no participants)" in {
      // Worker-shaped scheduling: workflow.conversationId points at
      // a conversation that has no participants on its own and
      // descends from a parent that does. Mirrors `delegate_task`'s
      // newConversation(participants = Nil, parentConversationId =
      // Some(parent)) shape so the framework's lifecycle hooks
      // exercise the empty-participants fallback path.
      val parentConvId = Conversation.id("init-fail-parent-1")
      val workerConvId = Conversation.id("init-fail-worker-1")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)

      val parentConv = Conversation(
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
        _id = parentConvId
      )
      val workerConv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = Nil,
        currentMode = ConversationMode,
        space = GlobalSpace,
        parentConversationId = Some(parentConvId),
        _id = workerConvId
      )

      val template = WorkflowTemplate(
        name = "first-step-throws",
        description = Some("Single step that calls a missing tool."),
        steps = List(JobStepInput(
          id   = "explode",
          name = Some("Explode on first attempt"),
          tool = Some("definitely_not_a_real_tool")
        )),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(workerConvId)
      )

      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(parentConv)))
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(workerConv)))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        _ <- waitFor(recorded, 10.seconds)(_.exists {
                case e: WorkflowRunFailed => e.runId == run._id.value
                case _ => false
              })
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val all = recorded.iterator().asScala.toList
        val starts = all.collect { case e: WorkflowRunStarted if e.runId == run._id.value => e }
        val fails  = all.collect { case e: WorkflowRunFailed  if e.runId == run._id.value => e }
        val ok     = all.collect { case e: WorkflowRunCompleted if e.runId == run._id.value => e }

        starts should have size 1
        fails should have size 1
        ok shouldBe empty
        fails.head.reason should not be empty
      }
    }

    "extract the StepFailure error message verbatim as the failure reason" in {
      // Pin the reason-extraction contract independently of the
      // happy / failure integration flows. Keeps the helper's
      // shape stable for downstream consumers reading
      // WorkflowRunFailed.reason — a single-line excerpt of the
      // underlying exception's getMessage, nothing more.
      val stepId = lightdb.id.Id[strider.step.Step]("step-1")
      val withFailure = strider.Workflow(
        name      = "reason-extract",
        steps     = Nil,
        scheduled = 0L,
        queue     = Nil,
        sourceId  = lightdb.id.Id[strider.WorkflowParent]("src"),
        history   = List(
          strider.WorkflowHistory(strider.WorkflowActivity.Completed(false)),
          strider.WorkflowHistory(strider.WorkflowActivity.StepFailure(stepId, "boom: concrete details"))
        )
      )
      sigil.workflow.SigilWorkflowManager.extractFailureReason(withFailure) shouldBe "boom: concrete details"

      val timedOut = withFailure.copy(history = List(
        strider.WorkflowHistory(strider.WorkflowActivity.Completed(false)),
        strider.WorkflowHistory(strider.WorkflowActivity.TimedOut(None))
      ))
      sigil.workflow.SigilWorkflowManager.extractFailureReason(timedOut) shouldBe "Workflow timed out"

      val empty = withFailure.copy(history = Nil)
      sigil.workflow.SigilWorkflowManager.extractFailureReason(empty) shouldBe "unknown"
      Task.unit.map(_ => succeed)
    }

    "still publish Started → StepCompleted → Completed on a healthy noop workflow" in {
      val convId = Conversation.id("init-fail-healthy-3")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()
      Thread.sleep(100)

      val template = WorkflowTemplate(
        name = "healthy-noop",
        description = Some("Single empty step — completes immediately."),
        steps = List(JobStepInput(id = "noop", name = Some("Noop step"))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(convId)
      )

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
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        _ <- waitFor(recorded, 10.seconds)(_.exists {
                case e: WorkflowRunCompleted => e.runId == run._id.value
                case _ => false
              })
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val all = recorded.iterator().asScala.toList
        val starts = all.collect { case e: WorkflowRunStarted   if e.runId == run._id.value => e }
        val ok     = all.collect { case e: WorkflowRunCompleted if e.runId == run._id.value => e }
        val fails  = all.collect { case e: WorkflowRunFailed    if e.runId == run._id.value => e }

        starts should have size 1
        ok should have size 1
        fails shouldBe empty
      }
    }
  }

  private def waitFor(recorded: ConcurrentLinkedQueue[Signal],
                      timeout: FiniteDuration)(predicate: List[Signal] => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = Task.defer {
      import scala.jdk.CollectionConverters.*
      val seen = predicate(recorded.iterator().asScala.toList)
      if (seen || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    }
    loop
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
