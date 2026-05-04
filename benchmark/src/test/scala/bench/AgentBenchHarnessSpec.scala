package bench

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{
  CallId, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.tool.core.{ChangeModeTool, CoreTools, RespondTool}
import sigil.tool.model.{ChangeModeInput, RespondInput}
import spec.{TestAgent, TestCodingMode, TestSigil, TestTopicEntry, TestUser}
import spice.http.HttpRequest

import scala.concurrent.duration.*

/**
 * Validator for [[AgentBenchHarness]]. The point is to verify the
 * harness captures the [[ConversationTrace]] shape benchmark scorers
 * rely on (per-turn events, tool invokes, final reply, persisted
 * Conversation, mode-change tracking) — *not* to validate model
 * behavior.
 *
 * Drives a deterministic [[ScriptedProvider]] so every run produces
 * the same trace; an earlier version used the local llama.cpp
 * server, which made the assertions ride on whether the model
 * happened to call `change_mode` that turn. Behavior tests against
 * live models belong in benchmark runners, not the framework's own
 * test suite.
 */
class AgentBenchHarnessSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  override implicit protected val testTimeout: FiniteDuration = 30.seconds

  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "fake")

  /** Provider that returns a deterministic event sequence per turn,
    * indexed by call count. The agent loop drives one provider call
    * per iteration; each iteration consumes the next scripted entry. */
  private final class ScriptedProvider(perCallEvents: List[List[ProviderEvent]]) extends Provider {
    private val cursor = new java.util.concurrent.atomic.AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("scripted provider — no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val idx    = cursor.getAndIncrement()
      val events = perCallEvents.lift(idx).getOrElse(
        // Past the end of the script: emit a no-op respond so the
        // agent loop settles cleanly if it self-loops.
        respondCall(s"end-$idx", "ok")
      )
      Stream.emits(events)
    }
  }

  /** Streaming `respond` shape: ToolCallStart → ContentBlockStart →
    * ContentBlockDelta → ToolCallComplete(RespondInput) → Done.
    *
    * Reuses the conversation's current topic label so
    * `resolveTopicPrelude`'s "no change" fast path fires and no
    * topic-classifier LLM round-trip happens — otherwise the
    * classifier would call the provider too, consuming our scripted
    * cursor positions out from under the test. */
  private def respondCall(callIdValue: String, content: String): List[ProviderEvent] = {
    val cid = CallId(callIdValue)
    List(
      ProviderEvent.ToolCallStart(cid, RespondTool.schema.name.value),
      ProviderEvent.ContentBlockStart(cid, "Text", arg = None),
      ProviderEvent.ContentBlockDelta(cid, content),
      ProviderEvent.ToolCallComplete(cid,
        RespondInput(topicLabel = TestTopicEntry.label, topicSummary = TestTopicEntry.summary, content = content)),
      ProviderEvent.Done(StopReason.Complete)
    )
  }

  /** Atomic `change_mode` shape: ToolCallStart →
    * ToolCallComplete(ChangeModeInput) → Done(ToolCall). The
    * orchestrator self-loops after this and the next provider call
    * settles the turn. */
  private def changeModeCall(callIdValue: String, modeName: String): List[ProviderEvent] = {
    val cid = CallId(callIdValue)
    List(
      ProviderEvent.ToolCallStart(cid, ChangeModeTool.schema.name.value),
      ProviderEvent.ToolCallComplete(cid, ChangeModeInput(mode = modeName)),
      ProviderEvent.Done(StopReason.ToolCall)
    )
  }

  private def makeAgent(provider: Provider): DefaultAgentParticipant = {
    TestSigil.setProvider(Task.pure(provider))
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = modelId,
      toolNames = CoreTools.coreToolNames :+ ChangeModeTool.schema.name,
      instructions = Instructions(),
      generationSettings = GenerationSettings(maxOutputTokens = Some(4000), temperature = Some(0.0))
    )
  }

  private def conversationFactory(agent: DefaultAgentParticipant): Id[Conversation] => Conversation =
    convId => Conversation(
      topics       = List(TestTopicEntry),
      _id          = convId,
      participants = List(agent)
    )

  private val harness: AgentBenchHarness = AgentBenchHarness(TestSigil, TestUser)

  "AgentBenchHarness" should {
    "capture a single-turn conversation as a ConversationTrace with the expected shape" in {
      val provider = new ScriptedProvider(List(respondCall("turn-1", "hi")))
      val agent    = makeAgent(provider)
      harness.runOneShot(conversationFactory(agent), "Reply with the single word 'hi'.").map { trace =>
        trace.turns should have size 1
        val turn = trace.turns.head
        turn.events should not be empty
        turn.toolInvokes should not be empty
        turn.finalReply.isDefined shouldBe true
        turn.replyText should not be empty
        trace.finalConversation._id shouldBe trace.conversationId
        succeed
      }
    }

    "carry context across turns and surface mode changes when the user asks for code" in {
      val provider = new ScriptedProvider(List(
        // Turn 1 — plain `respond`.
        respondCall("rs-1", "Got it — your favorite color is blue."),
        // Turn 2 first iteration — `change_mode → <coding>`.
        changeModeCall("cm-2", TestCodingMode.name),
        // Turn 2 second iteration (orchestrator self-loops past the
        // mode switch) — `respond` settles the turn.
        respondCall("rs-2", "def factorial(n: Int): Int = if (n <= 1) 1 else n * factorial(n - 1)")
      ))
      val agent = makeAgent(provider)
      val turns = List(
        "My favorite color is blue. Acknowledge in one short sentence.",
        "Write me a Scala function that computes the factorial of n."
      )
      harness.runConversation(conversationFactory(agent), turns).map { trace =>
        trace.turns should have size 2
        // Mode-change tracking: the harness captures the
        // `change_mode → <coding>` from turn 2.
        trace.allModeChanges.map(_.mode.name) should contain(TestCodingMode.name)
        trace.finalConversation.currentMode.name shouldBe TestCodingMode.name
        // Both turns produced an agent reply.
        trace.turns.flatMap(_.finalReply.toList) should have size 2
        succeed
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
