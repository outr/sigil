package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType,
  StopReason
}
import sigil.signal.{EventState, Signal}
import sigil.tool.{Tool, ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #87 — when the model emits multiple
 * `function_call`s with identical (toolName, args) in a single
 * completion (parallel hedging on a deterministic-failure tool,
 * etc.), the orchestrator runs the underlying execution ONCE and
 * routes the duplicate call_ids to a synthesized Tool-role pointer
 * Message. Wire shape stays well-formed; the tool body runs once.
 */
class ParallelToolCallDedupeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // -- a tool that records every executeTyped invocation --

  case class CountingInput(payload: String) extends ToolInput derives RW

  private val invocations = new AtomicInteger(0)

  case object CountingTool extends TypedTool[CountingInput](
    name        = ToolName("counting_tool"),
    description = "Records every executeTyped invocation."
  ) {
    override protected def executeTyped(input: CountingInput, ctx: TurnContext): Stream[Event] = {
      invocations.incrementAndGet()
      Stream.emit[Event](Message(
        participantId  = ctx.caller,
        conversationId = ctx.conversation.id,
        topicId        = ctx.conversation.currentTopicId,
        content        = Vector(ResponseContent.Text(s"ran with ${input.payload}")),
        role           = MessageRole.Tool,
        state          = EventState.Complete
      ))
    }
  }

  ToolInput.register(RW.static(CountingInput("")))

  /** Provider that emits the same (toolName, args) twice in a
    * single completion — simulating parallel hedging. */
  private class DuplicateCallProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val payload = CountingInput("hedged")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(CallId("call-1"), CountingTool.name.value),
        ProviderEvent.ToolCallComplete(CallId("call-1"), payload),
        ProviderEvent.ToolCallStart(CallId("call-2"), CountingTool.name.value),
        ProviderEvent.ToolCallComplete(CallId("call-2"), payload),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def buildRequest(convId: Id[Conversation]): ConversationRequest =
    ConversationRequest(
      conversationId     = convId,
      modelId            = Model.id("test", "dedupe-spec-model"),
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools              = Vector(CountingTool),
      chain              = List(TestUser, TestAgent)
    )

  "Orchestrator parallel-call dedupe (#87)" should {

    "execute the tool once even when the model emits two identical calls" in {
      invocations.set(0)
      val provider = new DuplicateCallProvider
      val convId = Conversation.id(s"dedupe-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = buildRequest(convId)
      for {
        _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      } yield {
        // Underlying execution ran exactly once.
        invocations.get() shouldBe 1

        // BOTH ToolInvokes are still emitted — the wire saw two
        // function_calls; the framework can't unilaterally drop them.
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        invokes should have size 2

        // Tool-role results paired to BOTH invokes — wire pairing
        // satisfied. Both should carry inlined content from the
        // original execution, NOT a "see that result" pointer text.
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages.size should be >= 2

        val rendered = toolMessages.flatMap(_.content).collect { case ResponseContent.Text(t) => t }
        // The genuine result text appears at least once (the original execution).
        rendered.exists(_.contains("ran with hedged")) shouldBe true
        // No call_id reference text leaks into the agent's context.
        all(rendered) should not include "see that result"
        all(rendered) should not include "(deduplicated:"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
