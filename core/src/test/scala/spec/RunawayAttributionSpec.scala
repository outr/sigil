package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{
  CallId, GenerationSettings, Instructions, Provider, ProviderCall,
  ProviderEvent, ProviderType, ReasoningMode, StopReason, ToolChoice
}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, CoreTools}
import sigil.tool.model.{ChangeModeInput, ResponseContent}
import spice.http.HttpRequest

import java.util.concurrent.atomic
import scala.concurrent.duration.*

/**
 * Regression coverage for the four agent-loop failure-attribution
 * bugs:
 *   - **#198** — `AgentRunawayException` carries the actual trigger
 *     reason (CapHit / NoToolCall / StallIntervention) and the
 *     surfaced Failure-Message text reflects it (no more "hit
 *     maxAgentIterations" misattribution when the real cause was
 *     "model never called any tool").
 *   - **#199** — when `forceResponseSynthesis` engages, the provider
 *     call's generation settings override `maxOutputTokens` and
 *     `reasoningMode = Off` so a local thinking-mode model can't
 *     burn unbounded reasoning tokens before the structured tool
 *     call lands.
 *   - **#200** — `publishFailureMessage` is CAS-gated, so a single
 *     thrown exception that propagates up through N recursion
 *     levels surfaces exactly ONE Failure Message in the chat, not
 *     N identical bubbles.
 */
class RunawayAttributionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with org.scalatest.BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setMaxAgentIterations(3)

  override protected def afterAll(): Unit = {
    TestSigil.resetMaxAgentIterations()
    super.afterAll()
  }

  private val modelId: Id[Model] = Model.id("test", "runaway-attribution")

  /**
   * Records each ProviderCall so the spec can inspect generation
   * settings used per turn.
   */
  final private class CallRecorder {
    val calls: atomic.AtomicReference[Vector[ProviderCall]] =
      new atomic.AtomicReference(Vector.empty)
    def record(input: ProviderCall): Unit = {
      calls.updateAndGet(_ :+ input)
      ()
    }
  }

  /**
   * Stubborn provider — always emits a non-terminal `change_mode`
   * (never `respond`). Drives the cap-hit path through the
   * forced-synthesis turn and into the hard-throw fallback.
   */
  final private class StubbornProvider(recorder: CallRecorder) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      recorder.record(input)
      val n = recorder.calls.get().size
      val callId = CallId(s"stubborn-$n")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, ChangeModeTool.schema.name.value),
        ProviderEvent.ToolCallComplete(callId, ChangeModeInput(mode = "conversation")),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def makeAgent(): AgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = ToolName("change_mode") :: CoreTools.coreToolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(
        maxOutputTokens = None,
        temperature = Some(0.0),
        reasoningMode = ReasoningMode.On
      )
    )

  private def eventsFor(convId: Id[Conversation]): Task[List[sigil.event.Event]] =
    TestSigil.withDB(_.events.transaction(_.list)).map(_.filter(_.conversationId == convId))

  private def driveCapHit(): Task[(CallRecorder, Id[Conversation], List[sigil.event.Event])] = {
    val recorder = new CallRecorder
    TestSigil.setProvider(Task.pure(new StubbornProvider(recorder)))
    val convId = Conversation.id(s"runaway-${rapid.Unique()}")
    val agent = makeAgent()
    val conv = Conversation(topics = TestTopicStack, participants = List(agent), _id = convId)
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(Message(
        participantId = TestUser,
        conversationId = convId,
        topicId = TestTopicEntry.id,
        content = Vector(ResponseContent.Text("Trigger cap-hit")),
        state = EventState.Complete
      ))
      _ <- Task.sleep(3.seconds)
      evs <- eventsFor(convId)
    } yield (recorder, convId, evs)
  }

  "AgentRunawayException attribution (sigil bug #198)" should {

    "surface a CapHit-attributed Failure Message after a stubborn cap-hit" in
      driveCapHit().map { case (_, _, evs) =>
        val failureMessages = evs.collect {
          case m: Message if m.isFailure && m.failureReason.exists(_.contains("AgentRunaway")) => m
        }
        failureMessages should not be empty
        val reason = failureMessages.head.failureReason.getOrElse("")
        reason should include("AgentRunaway")
        // CapHit message phrasing.
        reason should include("maxAgentIterations")
      }
  }

  "Forced-synthesis settings override (sigil bug #199)" should {

    "set bounded maxOutputTokens and ReasoningMode.Off on the forced-synthesis call" in
      driveCapHit().map { case (recorder, _, _) =>
        val recorded = recorder.calls.get().toList
        // The forced-synthesis call is the one with tool_choice =
        // Required restricted to the respond family.
        val forcedCall = recorded.find { c =>
          val respondFamily = sigil.tool.core.CoreTools.atomicContentToolNames
          c.toolChoice == ToolChoice.Required &&
          c.tools.exists(_.schema.name.value == "respond") &&
          c.tools.forall(t => respondFamily.contains(t.schema.name))
        }
        withClue(s"recorded ${recorded.size} calls; none matched the forced-respond pattern: ") {
          forcedCall should not be None
        }
        val gen = forcedCall.get.generationSettings
        gen.maxOutputTokens should not be empty
        gen.reasoningMode shouldBe ReasoningMode.Off
      }
  }

  "Single failure publish (sigil bug #200)" should {

    "publish exactly one AgentRunaway Failure Message even though the exception propagates through multiple recursion levels" in
      driveCapHit().map { case (_, _, evs) =>
        val runawayFailures = evs.collect {
          case m: Message
              if m.isFailure &&
                m.role == MessageRole.Standard &&
                m.failureReason.exists(_.contains("AgentRunaway")) => m
        }
        runawayFailures should have size 1
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
