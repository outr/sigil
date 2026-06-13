package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.GlobalSpace
import sigil.conversation.{Conversation, TopicEntry}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.{Message, ToolInvoke}
import sigil.participant.{AgentParticipantId, DefaultAgentParticipant}
import sigil.provider.{
  CallId, ConversationMode, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.model.ResponseContent
import sigil.workflow.{JobStepInput, LoopStepInput, WorkflowTemplate, WorkflowVariableSubstitution}
import sigil.workflow.event.{WorkflowRunCompleted, WorkflowRunFailed}
import spice.http.HttpRequest
import fabric.{Json, Str}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Sigil #381 / #382 — workflow lifecycle events link back to the bound
 * conversation (activity-pill discovery), a Loop body step's output threads to
 * a LATER body step in the same iteration, and an unresolved `{{var}}` in a
 * tool argument HARD-FAILS the step rather than being written (the destructive
 * bug: `write_file` overwrote real files with the literal `{{editedContents}}`).
 */
class WorkflowVarThreadingAndLinkSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "thread-model")
  TestWorkflowSigil.cache.merge(List(syntheticModel(modelId))).sync()

  private def syntheticModel(id: Id[Model]): Model = {
    val now = lightdb.time.Timestamp()
    Model(
      canonicalSlug = id.value, huggingFaceId = "", name = id.value,
      description = s"Synthetic Model for $id.", contextLength = 32768L,
      architecture = ModelArchitecture("text->text", List("text"), List("text"), "GPT", None),
      pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
      topProvider = ModelTopProvider(contextLength = Some(32768L), maxCompletionTokens = Some(8192L), isModerated = false),
      perRequestLimits = None, supportedParameters = Set("temperature"), knowledgeCutoff = None,
      expirationDate = None, links = ModelLinks(details = ""), created = now, _id = id
    )
  }

  /** Echoes a fixed reply for the consuming prompt step. */
  private object StubProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestWorkflowSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] = Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(ProviderEvent.ContentBlockDelta(CallId("s"), "ok"), ProviderEvent.Done(StopReason.Complete)))
  }

  private def boundConv(id: Id[Conversation]): Conversation =
    Conversation(
      topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
      participants = List(DefaultAgentParticipant(
        id = WorkflowTestUser.asInstanceOf[AgentParticipantId], modelId = modelId,
        toolNames = Nil, instructions = Instructions(), generationSettings = GenerationSettings())),
      currentMode = ConversationMode, space = GlobalSpace, _id = id
    )

  private def subscribe(): ConcurrentLinkedQueue[Signal] = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    TestWorkflowSigil.signals.evalMap(s => Task { recorded.add(s); () }).drain.startUnit()
    Thread.sleep(100)
    recorded
  }

  private def waitForTerminal(recorded: ConcurrentLinkedQueue[Signal], runId: String, timeout: FiniteDuration): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = Task.defer {
      import scala.jdk.CollectionConverters.*
      val done = recorded.iterator().asScala.exists {
        case e: WorkflowRunCompleted => e.runId == runId
        case e: WorkflowRunFailed    => e.runId == runId
        case _                       => false
      }
      if (done || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    }
    loop
  }

  "WorkflowVariableSubstitution.unresolvedVars (sigil #382)" should {
    "report variables still present as {{...}} after substitution" in Task {
      WorkflowVariableSubstitution.unresolvedVars("a {{foo}} b {{bar}} {{foo}}") shouldBe List("foo", "bar")
      WorkflowVariableSubstitution.unresolvedVars(WorkflowVariableSubstitution.substitute(
        "p={{path}} c={{missing}}", Map("path" -> (Str("x"): Json)))) shouldBe List("missing")
      WorkflowVariableSubstitution.unresolvedVars("nothing here") shouldBe Nil
    }
  }

  "Workflow Loop body output threading + lifecycle link" should {

    "thread a body step's output to a LATER body step in the same iteration (sigil #382)" in {
      TestWorkflowSigil.setProvider(Task.pure(StubProvider))
      val recorded = subscribe()
      val boundId = Conversation.id(s"thread-${rapid.Unique()}")
      // Body: produce `r1`, then a prompt step that references {{r1}}. If the
      // sibling output threads, the persisted prompt carries r1's value, not the
      // literal `{{r1}}`.
      val template = WorkflowTemplate(
        name = "loop-thread",
        steps = List(LoopStepInput(
          id = "loop", over = "items", itemVariable = "item", output = Some("processed"),
          body = List(
            JobStepInput(id = "make", tool = Some("echo_back"), arguments = Some("""{"text":"{{item}}"}"""), output = Some("r1")),
            JobStepInput(id = "use", prompt = Some("MARKER {{r1}}"), output = Some("r2"))))),
        space = GlobalSpace, createdBy = Some(WorkflowTestUser), conversationId = Some(boundId)
      )
      for {
        _   <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(boundConv(boundId))))
        _   <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template, variables = Map("items" -> fabric.arr(Str("x"))))
        _   <- waitForTerminal(recorded, run._id.value, 15.seconds)
        runConvId = run.conversationId.map(Id[Conversation](_))
        evs <- TestWorkflowSigil.withDB(_.events.transaction(_.list))
      } yield {
        import scala.jdk.CollectionConverters.*
        recorded.iterator().asScala.toList.collect { case e: WorkflowRunFailed if e.runId == run._id.value => e } shouldBe empty
        // The persisted prompt turn carries the resolved sibling output — not
        // the unresolved `{{r1}}` literal (which is exactly what broke before).
        val prompts = runConvId.toList.flatMap(cid => evs.collect {
          case m: Message if m.conversationId == cid =>
            m.content.collect { case ResponseContent.Text(t) => t }.mkString
        }).filter(_.startsWith("MARKER "))
        prompts should not be empty
        prompts.foreach(_ should not include "{{r1}}")
        succeed
      }
    }

    "HARD-FAIL a tool step with an unresolved {{var}} (never dispatch) and link the failure to the bound conversation (sigil #382/#381)" in {
      TestWorkflowSigil.setProvider(Task.pure(StubProvider))
      val recorded = subscribe()
      val boundId = Conversation.id(s"unresolved-${rapid.Unique()}")
      val template = WorkflowTemplate(
        name = "unresolved-arg",
        steps = List(JobStepInput(id = "echo", tool = Some("echo_back"), arguments = Some("""{"text":"{{nope}}"}"""))),
        space = GlobalSpace, createdBy = Some(WorkflowTestUser), conversationId = Some(boundId)
      )
      for {
        _   <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(boundConv(boundId))))
        _   <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        _   <- waitForTerminal(recorded, run._id.value, 15.seconds)
        runConvId = run.conversationId.map(Id[Conversation](_))
        evs <- TestWorkflowSigil.withDB(_.events.transaction(_.list))
      } yield {
        import scala.jdk.CollectionConverters.*
        val fails = recorded.iterator().asScala.toList.collect { case e: WorkflowRunFailed if e.runId == run._id.value => e }
        fails should have size 1
        // #382 — failed with an unresolved-variable reason, and the tool never ran.
        fails.head.reason.toLowerCase should include ("unresolved")
        runConvId.toList.flatMap(cid => evs.collect {
          case ti: ToolInvoke if ti.conversationId == cid && ti.toolName.value == "echo_back" => ti
        }) shouldBe empty
        // #381 — the failure event links back to the bound conversation.
        fails.head.parentConversationId shouldBe Some(boundId)
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
