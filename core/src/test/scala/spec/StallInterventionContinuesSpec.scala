package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.BeforeAndAfterAll
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole, MessageVisibility, ToolInvoke}
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
import sigil.tool.consult.ProgressReflectionTool

/**
 * Sigil #379 — a stall/progress-checkpoint intervention is a
 * NON-TERMINAL nudge, not a forced terminal respond. When the checkpoint
 * trips on a stall it publishes a Tool-role `_stall_detected` directive
 * (Agents visibility, bug #133) and then lets the agent CONTINUE with
 * its full roster — so it can change approach or ask the user itself via
 * `respond_options`. Forced synthesis (the narrowed respond-family
 * roster + the #375 `tool_choice = Specific(respond)` pin) fires ONLY at
 * the `maxAgentIterations` ceiling.
 *
 * Before #379 the stall path immediately forced a respond, which blocked
 * `respond_options` and, on an `endsTurn:false` reply, wedged the turn so
 * every "continue" re-fired the stale stall.
 */
class StallInterventionContinuesSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  // Checkpoint every 2 iterations; cap at 6 so the test terminates via
  // the ceiling (the only place that still forces synthesis).
  TestSigil.setProgressCheckpointInterval(2)
  TestSigil.setMaxAgentIterations(6)

  override protected def afterAll(): Unit = {
    TestSigil.resetProgressCheckpointInterval()
    TestSigil.resetMaxAgentIterations()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "stall-continue")
  TestSigil.testModel(modelId)

  /**
   * Emits an identical `change_mode` every main-loop turn so
   * `StallDetector` fires. On the checkpoint reflector call reports
   * `meaningfulProgress = false`. Only the iteration-cap forced turn
   * (signalled by `tool_choice = Specific(respond)`) gets a respond —
   * so the agent settles ONLY at the ceiling, never at a stall.
   */
  final private class StallEveryTurnProvider extends Provider {

    /**
     * Per-call (toolChoice, rosterHasChangeMode).
     */
    val calls: atomic.AtomicReference[Vector[(ToolChoice, Boolean)]] =
      new atomic.AtomicReference(Vector.empty)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val hasChangeMode = input.tools.exists(_.schema.name == ChangeModeTool.schema.name)
      calls.updateAndGet(_ :+ (input.toolChoice, hasChangeMode))
      val callId = CallId(s"call-${rapid.Unique()}")
      val isReflectorCall = input.tools.exists(_.name.value == "report_progress")
      val emits: List[ProviderEvent] =
        if (isReflectorCall)
          List(
            ProviderEvent.ToolCallStart(callId, "report_progress"),
            ProviderEvent.toolCall(callId, ProgressReflectionTool)(_root_.sigil.tool.consult.ProgressReflectionInput(
              currentStatus = "still looping on change_mode",
              meaningfulProgress = false,
              remainingSteps = "wrap up and respond",
              stuckOn = Some("looping"),
              shouldAskUser = false
            )),
            ProviderEvent.Done(StopReason.Complete)
          )
        else input.toolChoice match {
          case ToolChoice.Specific(name) if name == RespondTool.schema.name =>
            List(
              ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
              ProviderEvent.toolCall(callId, RespondTool)(RespondInput(
                topicLabel = "Cap-synth",
                topicSummary = "forced-synthesis at the ceiling",
                content = "Synthesised at the iteration ceiling.",
                endsTurn = true
              )),
              ProviderEvent.Done(StopReason.Complete)
            )
          case _ =>
            List(
              ProviderEvent.ToolCallStart(callId, ChangeModeTool.schema.name.value),
              // Vary the reason each turn so inputs differ: this drives a
              // COOPERATIVE no-progress checkpoint (the reflector reports
              // false) WITHOUT an identical-call streak — so the HARD-stall
              // (terminal) path never fires and we exercise the #379 continue
              // path, not the genuine-runaway force path.
              ProviderEvent.toolCall(callId, ChangeModeTool)(ChangeModeInput(mode = "coding", reason = Some(s"step-${rapid.Unique()}"))),
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

  "Stall-detected intervention (sigil #379)" should {

    "publish a `_stall_detected` directive and let the agent CONTINUE with its full roster, forcing only at the cap" in {
      val provider = new StallEveryTurnProvider
      TestSigil.setProvider(Task.pure(provider))
      val convId = Conversation.id(s"stall-${rapid.Unique()}")
      val agent = makeAgent()
      val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("Evaluate the X system")),
          state = EventState.Complete
        ))
        _ <- TestSigil.awaitSettled(convId)
        evs <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val convEvs = evs.filter(_.conversationId == convId)

        // The stall intervention still fires: a `_stall_detected`
        // Tool-role directive (Agents visibility) lands.
        val stallInvokes = convEvs.collect {
          case ti: ToolInvoke if ti.toolName.value == "_stall_detected" => ti
        }
        withClue(s"events: ${convEvs.map(_.getClass.getSimpleName).mkString(", ")}: ") {
          stallInvokes should not be empty
        }
        val directives = convEvs.collect {
          case m: Message if m.role == MessageRole.Tool && m.origin.contains(stallInvokes.head._id) => m
        }
        directives should not be empty
        directives.head.visibility shouldBe MessageVisibility.Agents

        val recorded = provider.calls.get().toList
        // #379 — the agent kept running FULL-roster main-loop iterations
        // (change_mode in scope) across the stall directives rather than
        // being forced to respond at the first stall. Pre-#379 it would
        // have been narrowed + forced after the first checkpoint (~2
        // full-roster calls); here it runs the budget up to the cap.
        val fullRosterCalls = recorded.count(_._2)
        withClue(s"toolChoices=${recorded.map(_._1).mkString(",")}: ") {
          fullRosterCalls should be >= 4
        }
        // The only Specific(respond) is the ceiling's forced synthesis,
        // and it carries the NARROWED roster (no change_mode) — no stall
        // ever pinned tool_choice to respond before the cap.
        recorded.filter(_._1 == ToolChoice.Specific(RespondTool.schema.name))
          .foreach { case (_, hasChangeMode) => hasChangeMode shouldBe false }

        // The agent ultimately settles — via the cap's forced synthesis.
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
