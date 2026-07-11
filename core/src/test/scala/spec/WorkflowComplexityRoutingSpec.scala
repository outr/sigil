package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.GlobalSpace
import sigil.conversation.{Conversation, TopicEntry}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.participant.{AgentParticipantId, DefaultAgentParticipant}
import sigil.provider.{
  CallId, Complexity, ConversationMode, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.workflow.{JobStepInput, WorkflowTemplate}
import sigil.workflow.event.{WorkflowRunCompleted, WorkflowRunFailed}
import spice.http.HttpRequest

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Sigil #380 — a workflow prompt step carries NO model id. Its model is routed
 * from the conversation's Mode (workType) + an optional complexity tier, with
 * the running agent's own (registered) model as the fallback. So a prompt step
 * with no model id no longer throws UnregisteredModelException — it routes and
 * runs like a normal turn.
 */
class WorkflowComplexityRoutingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestWorkflowSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "route-model")
  TestWorkflowSigil.cache.merge(List(syntheticModel(modelId))).sync()

  private def syntheticModel(id: Id[Model]): Model = {
    val now = lightdb.time.Timestamp()
    Model(
      canonicalSlug = id.value,
      huggingFaceId = "",
      name = id.value,
      description = s"Synthetic Model for $id.",
      contextLength = 32768L,
      architecture = ModelArchitecture("text->text", List("text"), List("text"), "GPT", None),
      pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
      topProvider = ModelTopProvider(contextLength = Some(32768L), maxCompletionTokens = Some(8192L), isModerated = false),
      perRequestLimits = None,
      supportedParameters = Set("temperature", "tools"),
      knowledgeCutoff = None,
      expirationDate = None,
      links = ModelLinks(details = ""),
      created = now,
      _id = id
    )
  }

  /**
   * Returns a fixed reply for the OneShot prompt call.
   */
  private object StubProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestWorkflowSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(
        ProviderEvent.ContentBlockDelta(CallId("route-0"), "routed reply"),
        ProviderEvent.Done(StopReason.Complete)
      ))
  }

  private def waitFor(recorded: ConcurrentLinkedQueue[Signal], timeout: FiniteDuration)(p: List[Signal] => Boolean): Task[Unit] = {
    val deadline = System.currentTimeMillis() + timeout.toMillis
    def loop: Task[Unit] = Task.defer {
      import scala.jdk.CollectionConverters.*
      if (p(recorded.iterator().asScala.toList) || System.currentTimeMillis() > deadline) Task.unit
      else Task.sleep(100.millis).flatMap(_ => loop)
    }
    loop
  }

  "Workflow prompt-step routing (sigil #380)" should {

    "run a prompt step that carries NO modelId — routed by mode + complexity to a registered model" in {
      TestWorkflowSigil.setProvider(Task.pure(StubProvider))
      val boundId = Conversation.id(s"route-${rapid.Unique()}")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestWorkflowSigil.signals.evalMap(s => Task { recorded.add(s); () }).takeWhile(_ => running).drain.startUnit()
      Thread.sleep(100)

      val conv = Conversation(
        topics = List(TopicEntry(WorkflowTestTopic.id, WorkflowTestTopic.label, WorkflowTestTopic.summary)),
        participants = List(DefaultAgentParticipant(
          id = WorkflowTestUser.asInstanceOf[AgentParticipantId],
          modelId = modelId,
          toolNames = Nil,
          instructions = Instructions(),
          generationSettings =
            GenerationSettings()
        )),
        currentMode = ConversationMode,
        space = GlobalSpace,
        _id = boundId
      )
      // A prompt step with NO model id, only an (optional) complexity tier.
      val template = WorkflowTemplate(
        name = "route-prompt",
        description = Some("Single prompt step, no model id."),
        steps = List(JobStepInput(
          id = "ask",
          name = Some("Ask"),
          prompt = Some("hello"),
          output = Some("r"),
          complexity = Some(Complexity.Low))),
        space = GlobalSpace,
        createdBy = Some(WorkflowTestUser),
        conversationId = Some(boundId)
      )
      for {
        _ <- TestWorkflowSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestWorkflowSigil.withDB(_.workflowTemplates.transaction(_.upsert(template)))
        run <- sigil.workflow.WorkflowScheduler.scheduleTemplate(TestWorkflowSigil, template)
        _ <- waitFor(recorded, 15.seconds)(_.exists {
          case e: WorkflowRunCompleted => e.runId == run._id.value
          case e: WorkflowRunFailed => e.runId == run._id.value
          case _ => false
        })
        wf <- TestWorkflowSigil.withDB(_.workflows.transaction(_.get(run._id)))
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val all = recorded.iterator().asScala.toList
        // The run completed — no UnregisteredModelException despite no modelId.
        all.collect { case e: WorkflowRunFailed if e.runId == run._id.value => e } shouldBe empty
        all.collect { case e: WorkflowRunCompleted if e.runId == run._id.value => e } should have size 1
        // The routed model produced the step's output.
        wf.flatMap(_.variables.get("r")).map(_.asString) shouldBe Some("routed reply")
      }
    }
  }

  "tear down" should {
    "dispose TestWorkflowSigil" in TestWorkflowSigil.shutdown.map(_ => succeed)
  }
}
