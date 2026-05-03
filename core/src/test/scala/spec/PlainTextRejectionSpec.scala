package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.ToolName
import sigil.tool.core.{NoResponseTool, RespondTool}
import sigil.tool.model.{NoResponseInput, ResponseContent}
import spice.http.HttpRequest

/**
 * Regression coverage for bug #75 — when the model emits plain text
 * without any tool call (a real failure mode for smaller / quantised
 * local models that drift on `tool_choice: required`), the framework
 * used to silently drop the text and bug-#46's `(agent completed
 * without a reply)` placeholder fired post-loop with no feedback to
 * the model.
 *
 * Post-fix: the orchestrator's `Done` handler emits a synthetic
 * ToolInvoke + Tool-role Message carrying
 * `ResponseContent.Failure(reason, recoverable = true)`. The
 * Tool-role tag makes it a trigger (`TriggerFilter.isTriggerFor`
 * always fires on Tool role), so the agent re-iterates with the
 * diagnostic in its history. The agent reads
 * "your reply was plain text — wrap in a respond-family tool" and
 * can self-correct on the next iteration.
 */
class PlainTextRejectionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Provider that emits plain text deltas with no tool call —
    * mirrors gemma-26B-A4B-Q4's drift behaviour from the bug's
    * wire log: `delta.content` fragments + `finish_reason: stop`,
    * no `delta.tool_calls`. */
  private class PlainTextProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(
        ProviderEvent.TextDelta("!"),
        ProviderEvent.TextDelta("["),
        ProviderEvent.TextDelta("Random Dog]("),
        ProviderEvent.TextDelta("https://example.com/dog.jpg"),
        ProviderEvent.TextDelta(")"),
        ProviderEvent.Done(StopReason.Complete)
      ))
  }

  /** Provider that emits both plain text AND a tool call — the
    * framework should NOT emit a drift diagnostic in this case
    * (some providers leak narration alongside tool args; that's
    * not a policy violation). */
  private class TextAndToolCallProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("ok-call")
      Stream.emits(List(
        ProviderEvent.TextDelta("(narrating)"),
        ProviderEvent.ToolCallStart(cid, NoResponseTool.schema.name.value),
        ProviderEvent.ToolCallComplete(cid, NoResponseInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  /** Provider that emits nothing at all — true silent completion.
    * No drift diagnostic should fire (there's no plain text to
    * reject). */
  private class TrulySilentProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emit(ProviderEvent.Done(StopReason.Complete))
  }

  private def runWith(provider: Provider, suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"plain-text-reject-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = Model.id("test", "model"),
      instructions       = Instructions(),
      turnInput          = TurnInput(ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      chain              = List(TestUser, TestAgent),
      tools              = Vector(NoResponseTool, RespondTool)
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Bug #75 — plain text without a tool call" should {
    "emit a synthetic ToolInvoke + Tool-role Message with ResponseContent.Failure" in {
      runWith(new PlainTextProvider, "drift").map { signals =>
        // Synthetic ToolInvoke is present, with the framework-
        // internal name and `internal = true` flag.
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        invokes should have size 1
        invokes.head.toolName shouldBe ToolName("_plain_text_reply")
        invokes.head.internal shouldBe true

        // Tool-role Message with Failure content paired to that invoke.
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        val msg = toolMessages.head
        msg.origin shouldBe Some(invokes.head._id)
        // ResponseContent.Failure carries the diagnostic — apps
        // pattern-matching on Failure pick this up automatically.
        val failure = msg.content.collectFirst { case f: ResponseContent.Failure => f }
        failure shouldBe defined
        failure.get.recoverable shouldBe true
        failure.get.reason should include ("plain text")
        failure.get.reason should include ("respond-family")
        // The dropped text is included so the agent can see what it
        // tried to say and reformulate as a tool call.
        failure.get.reason should include ("Random Dog")
        succeed
      }
    }

    "NOT emit a diagnostic when plain text accompanies a tool call (some providers leak narration)" in {
      runWith(new TextAndToolCallProvider, "narration-ok").map { signals =>
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        // Only the real tool call; no synthetic _plain_text_reply.
        invokes.map(_.toolName) shouldNot contain (ToolName("_plain_text_reply"))
        // No Tool-role Message carrying a Failure either.
        val failures = signals.collect {
          case m: Message if m.role == MessageRole.Tool =>
            m.content.collect { case f: ResponseContent.Failure => f }
        }.flatten
        failures shouldBe empty
        succeed
      }
    }

    "NOT emit a diagnostic when the stream is truly silent (no text, no tool call)" in {
      runWith(new TrulySilentProvider, "silent").map { signals =>
        // No synthetic invoke; bug-#46's placeholder fires later via
        // `runAgentLoop.ensureSilentTurnReply`. The orchestrator's
        // Done handler stays clean — drift detection only fires
        // when there's real text to point at.
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        invokes.map(_.toolName) shouldNot contain (ToolName("_plain_text_reply"))
        succeed
      }
    }
  }
}
