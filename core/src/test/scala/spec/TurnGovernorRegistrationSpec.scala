package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.BeforeAndAfterAll
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.ForcedSynthesisReason
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.governor.{GovernorContext, GovernorVote, TurnGovernor}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, CoreTools, RespondTool}
import sigil.tool.model.{ChangeModeInput, ResponseContent, RespondInput}
import spice.http.HttpRequest

import java.util.concurrent.atomic

/**
 * An app-registered [[TurnGovernor]] participates in the agent loop's
 * iteration-boundary fold exactly like the built-ins: it is evaluated in
 * list order, its vote is honoured, and a non-Proceed vote short-circuits
 * every governor after it at that boundary.
 */
class TurnGovernorRegistrationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  // Checkpointing off and no budgets configured, so both built-in
  // governors Proceed at every boundary — the only intervention in this
  // turn is the registered one. The cap sits well above the forcing
  // boundary so the ceiling never competes for the outcome.
  TestSigil.setProgressCheckpointInterval(0)
  TestSigil.setMaxAgentIterations(10)

  override protected def afterAll(): Unit = {
    TestSigil.resetTurnGovernors()
    TestSigil.resetProgressCheckpointInterval()
    TestSigil.resetMaxAgentIterations()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "turn-governor")
  TestSigil.testModel(modelId)

  /**
   * Votes forced synthesis at one specific boundary; records every
   * boundary it is asked about.
   */
  final private class ForceAtGovernor(at: Int) extends TurnGovernor {
    val seen: atomic.AtomicReference[Vector[Int]] = new atomic.AtomicReference(Vector.empty)
    override def name: String = "test-force-at"
    override def evaluate(ctx: GovernorContext): Task[GovernorVote] = Task {
      seen.updateAndGet(_ :+ ctx.nextIteration)
      if (ctx.nextIteration == at)
        GovernorVote.Intervene(Task.unit, Some(ForcedSynthesisReason.StallIntervention))
      else GovernorVote.Proceed
    }
  }

  /**
   * Passes through to a built-in governor, recording each boundary it
   * actually reaches — the evidence for short-circuiting.
   */
  final private class RecordingGovernor(delegate: TurnGovernor) extends TurnGovernor {
    val seen: atomic.AtomicReference[Vector[Int]] = new atomic.AtomicReference(Vector.empty)
    override def name: String = s"recording-${delegate.name}"
    override def evaluate(ctx: GovernorContext): Task[GovernorVote] = Task {
      seen.updateAndGet(_ :+ ctx.nextIteration)
    }.flatMap(_ => delegate.evaluate(ctx))
  }

  /**
   * Emits a distinct non-terminal `change_mode` every main-loop turn so
   * the loop keeps iterating; responds only on the forced-synthesis
   * turn (signalled by `tool_choice = Specific(respond)`).
   */
  final private class LoopingProvider extends Provider {

    /**
     * `tool_choice` of each MAIN-LOOP call, in order. Auxiliary consult
     * calls (topic classification, memory extraction) carry their own
     * single-tool roster and are excluded so indices stay meaningful.
     */
    val toolChoices: atomic.AtomicReference[Vector[ToolChoice]] =
      new atomic.AtomicReference(Vector.empty)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      if (input.tools.exists(_.schema.name == RespondTool.schema.name))
        toolChoices.updateAndGet(_ :+ input.toolChoice)
      val callId = CallId(s"call-${rapid.Unique()}")
      val emits: List[ProviderEvent] = input.toolChoice match {
        case ToolChoice.Specific(name) if name == RespondTool.schema.name =>
          List(
            ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
            ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
              topicLabel = "Governor-synth",
              topicSummary = "forced by the registered governor",
              content = "Synthesised after the registered governor forced the wrap-up.",
              endsTurn = true
            )),
            ProviderEvent.Done(StopReason.Complete)
          )
        case _ =>
          List(
            ProviderEvent.ToolCallStart(callId, ChangeModeTool.schema.name.value),
            ProviderEvent.toolCall(callId, ChangeModeTool)(
              ChangeModeInput(mode = "coding", reason = Some(s"step-${rapid.Unique()}"))),
            ProviderEvent.Done(StopReason.ToolCall)
          )
      }
      Stream.emits(emits)
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0))
    )

  "A registered TurnGovernor" should {

    "be evaluated in list order, short-circuit later governors, and have its forced-synthesis vote honoured" in {
      val provider = new LoopingProvider
      TestSigil.setProvider(Task.pure(provider))
      // First in the roster; the built-ins follow, wrapped so we can see
      // whether they were reached at the forcing boundary.
      val forcing = new ForceAtGovernor(at = 3)
      val trailing = TestSigil.defaultTurnGovernors.map(new RecordingGovernor(_))
      TestSigil.setTurnGovernors(forcing :: trailing)

      val convId = Conversation.id(s"governor-${rapid.Unique()}")
      val agent = makeAgent()
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("Work the X system")),
          state = EventState.Complete
        ))
        _ <- TestSigil.awaitSettled(convId)
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val convEvs = evs.filter(_.conversationId == convId)
        val boundaries = forcing.seen.get()

        // The registered governor ran at every boundary up to and
        // including the one it claimed, and nothing after it.
        withClue(s"boundaries=$boundaries: ") {
          boundaries should contain(3)
          boundaries.last shouldBe 3
        }

        // List order + short-circuit: each built-in behind the forcing
        // governor saw every earlier boundary but NOT the claimed one.
        trailing.foreach { recorder =>
          val reached = recorder.seen.get()
          withClue(s"${recorder.name} reached=$reached (forcing saw $boundaries): ") {
            reached should not contain 3
            reached shouldBe boundaries.init
          }
        }

        // The vote was honoured, and at exactly the boundary that cast
        // it: forced synthesis pins `tool_choice` to the respond family,
        // and that pin lands on the THIRD main-loop call — iterations 1
        // and 2 ran normally, then the boundary-3 vote forced the
        // wrap-up.
        val choices = provider.toolChoices.get()
        withClue(s"toolChoices=$choices: ") {
          choices.indexOf(ToolChoice.Specific(RespondTool.schema.name)) shouldBe 2
        }

        val agentReplies = convEvs.collect {
          case m: Message if m.participantId == TestAgent && m.role == MessageRole.Standard => m
        }
        agentReplies should not be empty
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
