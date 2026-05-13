package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageDisposition, MessageRole}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.{Tool, ToolInput, ToolName}
import spice.http.HttpRequest

/**
 * Coverage for sigil bug #167 — every dispatched `ToolCallComplete`
 * must produce at least one `MessageRole.Tool`-shaped event so the
 * frame builder emits a paired `ContextFrame.ToolResult` and the
 * downstream renderer ships a `function_call_output` to OpenAI's
 * Responses API. Two paths previously dropped the result event:
 *
 *   1. Tool name not in the agent's roster — orchestrator's
 *      `case None => Stream.empty` silently produced nothing.
 *   2. Registered tool whose `execute` returned an empty stream
 *      (silent-failure path).
 *
 * Both now produce a Tool-role Failure Message so the wire's
 * call ↔ output pairing stays intact.
 */
class OrchestratorUnpairedToolCallSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class EmptyToolInput() extends ToolInput derives RW

  /** Tool whose execute returns Stream.empty — simulates a tool whose
    * executeTyped path swallowed an error or filtered everything out. */
  private object SilentTool extends Tool {
    override def name: ToolName = ToolName("silent_tool")
    override def description: String = "Returns nothing."
    override def inputRW: RW[? <: ToolInput] = summon[RW[EmptyToolInput]]
    override def execute(input: ToolInput, context: TurnContext): Stream[Event] = Stream.empty
  }

  private val modelId: Id[Model] = Model.id("test", "model")

  /** Provider that emits ToolCallComplete for whatever toolName the
    * test asks for, with empty args. */
  private class FakeProvider(toolName: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("c1")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, toolName),
        ProviderEvent.ToolCallComplete(cid, EmptyToolInput()),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def runWith(provider: Provider, tools: Vector[Tool], suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"unpaired-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = modelId,
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      previousTopics     = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      chain              = List(TestUser, TestAgent),
      tools              = tools
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Orchestrator (Bug #167) for an unknown tool name" should {

    "emit a Tool-role Failure Message paired to the invoke when the tool isn't in the roster" in {
      runWith(new FakeProvider("not_a_real_tool"), tools = Vector.empty, "unknown").map { signals =>
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        val msg = toolMessages.head
        msg.disposition shouldBe a [MessageDisposition.Failure]
        msg.failureReason.getOrElse("") should include ("Unknown tool")
        msg.failureReason.getOrElse("") should include ("not_a_real_tool")
        msg.origin shouldBe defined
      }
    }
  }

  "Orchestrator (Bug #167) for a registered tool whose execute is silent" should {

    "append a synthetic Tool-role Failure when the tool's execute returns Stream.empty" in {
      runWith(new FakeProvider("silent_tool"), tools = Vector(SilentTool), "silent").map { signals =>
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        val msg = toolMessages.head
        msg.disposition shouldBe a [MessageDisposition.Failure]
        msg.failureReason.getOrElse("") should include ("emitted no result")
        msg.failureReason.getOrElse("") should include ("silent_tool")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
