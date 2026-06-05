package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageDisposition}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, MessageDelta, Signal}
import spice.http.HttpRequest

/**
 * Regression for sigil bug #171 part A — when a respond-family tool
 * call starts streaming content (creating an in-flight Message at
 * `state = Active` from the ContentBlockDelta path) and then the
 * provider stream fails (parse error or throw), the in-flight Message
 * used to stay `Active` forever — UI showed a phantom "agent is
 * typing" bubble next to the recovery Message.
 *
 * Fix: the orchestrator's `ProviderEvent.Error` handler AND
 * `onErrorFinalize` (catching thrown ProviderStreamException etc.)
 * both call `settleOrphanMessage` to emit a terminal MessageDelta
 * with `state = Complete` and `disposition = Failure(recoverable)`,
 * so the bubble settles rather than hanging as a typing indicator.
 *
 * Sigil #361 — on the `ProviderEvent.Error` path the same failure is
 * ALSO routed to the agent as a recoverable Tool failure (it retries
 * and produces the real reply), so the streamed placeholder settles
 * with EMPTY content (collapsed) instead of a user-facing "failed to
 * produce a valid reply" dead-end. The settle-to-Complete + Failure
 * disposition (the #171 anti-typing-hang guarantee) is unchanged.
 */
class OrphanStreamingMessageSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "orphan-msg-model")

  /** Provider that streams a ToolCallStart + ContentBlockStart +
    * ContentBlockDelta (creating the in-flight Message), THEN emits
    * a ProviderEvent.Error before any ToolCallComplete settles the
    * Message. Mirrors the wire-log captured failure mode where
    * args parse failure fired after the respond Message was already
    * being streamed to the UI. */
  private class MidStreamErrorProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("call-orphan-msg")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, "respond"),
        ProviderEvent.ContentBlockStart(cid, "Markdown", arg = None),
        ProviderEvent.ContentBlockDelta(cid, "Streaming text before parse failure..."),
        ProviderEvent.Error("Failed to parse args for tool respond: simulated"),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def runOrchestrator(): Task[List[Signal]] = {
    val convId = Conversation.id("orphan-msg-test")
    val conv   = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId     = convId,
      model            = TestSigil.testModel(modelId),
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = Vector.empty
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new MidStreamErrorProvider, request, conv).toList
    } yield signals
  }

  "Bug #171.A — orphan streaming Message cleanup" should {

    "create the in-flight Message at Active state on ContentBlockDelta" in {
      runOrchestrator().map { signals =>
        val messages = signals.collect { case m: Message => m }
        // First Message creation is the streaming respond placeholder.
        messages should not be empty
        messages.head.state shouldBe EventState.Active
      }
    }

    "emit a terminal MessageDelta with state=Complete after the parse failure" in {
      runOrchestrator().map { signals =>
        val streamingMsg = signals.collectFirst { case m: Message => m }
          .getOrElse(fail("expected a streaming Message"))
        val terminalForStreaming = signals.collect {
          case d: MessageDelta if d.target == streamingMsg._id && d.state.contains(EventState.Complete) => d
        }
        terminalForStreaming should not be empty
      }
    }

    "stamp the terminal MessageDelta with Failure disposition" in {
      runOrchestrator().map { signals =>
        val streamingMsg = signals.collectFirst { case m: Message => m }
          .getOrElse(fail("expected a streaming Message"))
        val terminal = signals.collect {
          case d: MessageDelta if d.target == streamingMsg._id && d.state.contains(EventState.Complete) => d
        }.headOption.getOrElse(fail("no terminal delta for streaming Message"))
        terminal.disposition shouldBe defined
        terminal.disposition.get shouldBe a [MessageDisposition.Failure]
      }
    }

    "settle the streamed placeholder with empty content — failure routed to the agent (#361)" in {
      runOrchestrator().map { signals =>
        val streamingMsg = signals.collectFirst { case m: Message => m }
          .getOrElse(fail("expected a streaming Message"))
        val terminal = signals.collect {
          case d: MessageDelta if d.target == streamingMsg._id && d.state.contains(EventState.Complete) => d
        }.headOption.getOrElse(fail("no terminal delta for streaming Message"))
        // #361 — no user-facing dead-end text; the agent receives the
        // recoverable Tool failure and retries. The placeholder collapses
        // to empty content (still Complete + Failure so typing stops).
        terminal.contentReplacement shouldBe Some(Vector.empty)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
