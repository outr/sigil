package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole, ToolInvoke, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType,
  StopReason
}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.{TextToolOutput, Tool, ToolInput, ToolName, ToolResult}
import sigil.tool.ToolContext
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

  // -- a tool that records every execution --

  case class CountingInput(payload: String) extends ToolInput derives RW

  private val invocations = new AtomicInteger(0)

  case object CountingTool extends Tool {
    type Input = CountingInput
    type Output = TextToolOutput
    val inputRW = summon[RW[CountingInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName("counting_tool")
    val description = "Records every execution."

    override def executeResult(input: CountingInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task {
        invocations.incrementAndGet()
        ToolResult.Success(TextToolOutput(s"ran with ${input.payload}"))
      }
  }

  ToolInput.register(RW.static(CountingInput("")))

  /**
   * Provider that emits the same (toolName, args) twice in a
   * single completion — simulating parallel hedging.
   */
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
      conversationId = convId,
      model = TestSigil.testModel(Model.id("test", "dedupe-spec-model")),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools = Vector(CountingTool),
      chain = List(TestUser, TestAgent)
    )

  "Orchestrator parallel-call dedupe (#87)" should {

    "execute the tool once even when the model emits two identical calls" in {
      invocations.set(0)
      val provider = new DuplicateCallProvider
      val convId = Conversation.id(s"dedupe-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = buildRequest(convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
      } yield {
        // Underlying execution ran exactly once.
        invocations.get() shouldBe 1

        // BOTH ToolInvokes are still emitted — the wire saw two
        // function_calls; the framework can't unilaterally drop them.
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        invokes should have size 2

        // Tool-role results paired to BOTH invokes — wire pairing
        // satisfied. The genuine execution settles its invoke via a
        // ToolDelta carrying the typed payload; the deduplicated call
        // gets a Tool-role Message inlining that same result content.
        val settledDeltas = signals.collect {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        }
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        // One typed result for the genuine execution + one inlined
        // dupe Message — both invokes are paired.
        (settledDeltas.size + toolMessages.size) should be >= 2

        // Result text — from the genuine settling ToolDelta's typed
        // output and from the dupe Message's inlined content. Both
        // carry the original execution's output, NOT a "see that
        // result" pointer.
        val rendered =
          settledDeltas.flatMap(_.output).collect { case TextToolOutput(t) => t } ++
            toolMessages.flatMap(_.content).collect { case ResponseContent.Text(t) => t }
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
