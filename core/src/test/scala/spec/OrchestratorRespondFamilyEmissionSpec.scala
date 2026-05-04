package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ConversationView, Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, MessageDelta, Signal, ToolDelta}
import sigil.tool.ToolName
import sigil.tool.core.{NoResponseTool, RespondTool}
import sigil.tool.model.{NoResponseInput, RespondInput}
import spice.http.HttpRequest

/**
 * Regression for bug #56 — `respond` / `respond_options` /
 * `respond_field` / `respond_failure` / `no_response` are the
 * framework's speech mechanism, not "tool calls" in the user-visible
 * sense. The orchestrator emits `ToolInvoke` + `ToolDelta` pairs as
 * before (the framework's own logic — silent-turn detection, event
 * persistence — relies on them) but flags both with `internal = true`
 * so client UIs filter the chip and avoid rendering the same content
 * twice. The user-facing `Message` + `MessageDelta` is the
 * authoritative speech surface.
 */
class OrchestratorRespondFamilyEmissionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /** Provider that emits a streaming `respond` call: ToolCallStart,
    * ContentBlockStart, ContentBlockDelta(text), ToolCallComplete,
    * Done. Mirrors the live OpenAI Responses API shape. */
  private class StreamingRespondProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("respond-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, RespondTool.schema.name.value),
        ProviderEvent.ContentBlockStart(callId, "Text", arg = None),
        ProviderEvent.ContentBlockDelta(callId, "Hello, world."),
        ProviderEvent.ToolCallComplete(
          callId,
          RespondInput(topicLabel = "Greeting", topicSummary = "A friendly hello", content = "Hello, world.")
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Atomic `no_response` — agent decides not to speak. Same
    * suppression rule applies: no ToolInvoke / ToolDelta on the
    * wire. */
  private class AtomicNoResponseProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("noresp-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, NoResponseTool.schema.name.value),
        ProviderEvent.ToolCallComplete(callId, NoResponseInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def runWith(provider: Provider, suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"respond-emit-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(view),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = Vector(RespondTool, NoResponseTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Orchestrator (bug #56)" should {
    "flag the streaming respond call's ToolInvoke and settle ToolDelta as internal" in {
      runWith(new StreamingRespondProvider, suffix = "respond").map { signals =>
        val invokes = signals.collect { case t: ToolInvoke => t }
        invokes should have size 1
        invokes.head.toolName.value shouldBe "respond"
        invokes.head.internal shouldBe true

        val deltas = signals.collect { case d: ToolDelta => d }
        deltas should have size 1
        deltas.head.target shouldBe invokes.head._id
        deltas.head.internal shouldBe true

        // The user-facing Message + its settling MessageDelta still
        // arrive — the speech surface is unchanged.
        val messages = signals.collect { case m: Message if m.participantId == TestAgent => m }
        messages should have size 1
        val settles = signals.collect {
          case md: MessageDelta if md.state.contains(EventState.Complete) && md.contentReplacement.isDefined => md
        }
        settles should have size 1
        settles.head.target shouldBe messages.head._id
      }
    }

    "flag the atomic no_response call's ToolInvoke and ToolDelta as internal" in {
      runWith(new AtomicNoResponseProvider, suffix = "noresp").map { signals =>
        val invokes = signals.collect { case t: ToolInvoke => t }
        invokes should have size 1
        invokes.head.internal shouldBe true

        val deltas = signals.collect { case d: ToolDelta => d }
        all(deltas.map(_.internal)) shouldBe true
      }
    }

    "leave non-respond-family tool calls' internal flag false (default)" in {
      runWith(new FindCapabilityProvider, suffix = "fc").map { signals =>
        val invokes = signals.collect { case t: ToolInvoke => t }
        invokes should have size 1
        invokes.head.internal shouldBe false
      }
    }
  }

  /** Sanity that the flag isn't getting set for everything — only
    * the framework's terminal speech tools should be marked.
    * Outer-class so the type isn't path-dependent. */
  private class FindCapabilityProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("fc-call")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "find_capability"),
        ProviderEvent.ToolCallComplete(
          callId,
          _root_.sigil.tool.core.FindCapabilityInput(keywords = "test")
        ),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
