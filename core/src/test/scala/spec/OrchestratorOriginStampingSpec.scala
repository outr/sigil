package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageDisposition, MessageRole, ToolInvoke, ToolResults}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}
import sigil.tool.model.NoResponseInput
import spice.http.HttpRequest
import fabric.rw.*

/**
 * Orchestrator-side coverage of the `Event.origin` invariant
 * introduced in bug #69. The atomic-tool path (`executeAtomic`)
 * builds exactly one paired result event from the tool's
 * [[ToolResult]] resolution and stamps `origin = invokeId` on it —
 * guaranteeing the framework-built result event always pairs with
 * its parent ToolInvoke at the FrameBuilder boundary.
 *
 * Verifies:
 *   - A tool resolving to `Success` → the `ToolResults` result event
 *     carries `origin = invokeId`.
 *   - A tool resolving to `Failure` → the Tool-role Failure Message
 *     carries `origin = invokeId`.
 */
class OrchestratorOriginStampingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Tool that resolves to a typed `Success` — the framework builds a
    * `ToolResults` event paired to the dispatching ToolInvoke. */
  private object SuccessTool extends Tool {
    type Input  = NoResponseInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[NoResponseInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name        = ToolName("success_origin_test")
    val description = "Resolves to a typed Success."
    override def executeResult(input: NoResponseInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("done")))
  }

  /** Tool that resolves to a logical `Failure` — the framework builds
    * a Tool-role Failure Message paired to the dispatching ToolInvoke. */
  private object FailureTool extends Tool {
    type Input  = NoResponseInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[NoResponseInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name        = ToolName("failure_origin_test")
    val description = "Resolves to a logical Failure."
    override def executeResult(input: NoResponseInput, context: TurnContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.failure("deliberate failure for origin coverage"))
  }

  private class StubProvider(toolName: String, callIdValue: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[_root_.sigil.db.Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(callIdValue)
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, toolName),
        ProviderEvent.ToolCallComplete(cid, NoResponseInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def runWith(provider: Provider, tools: Vector[Tool], suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"origin-stamp-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = Model.id("test", "model"),
      instructions       = Instructions(),
      turnInput          = TurnInput(ConversationView(conversationId = convId)),
      currentMode        = ConversationMode,
      currentTopic       = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain              = List(TestUser, TestAgent),
      tools              = tools
    )
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Orchestrator.executeAtomic origin stamping (bug #69)" should {

    "stamp origin = invokeId on the ToolResults event a Success tool produces" in {
      runWith(
        provider = new StubProvider(SuccessTool.name.value, "success-call"),
        tools    = Vector(SuccessTool),
        suffix   = "success"
      ).map { signals =>
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        invokes should have size 1
        val invokeId = invokes.head._id

        val results = signals.collect { case tr: ToolResults => tr }
        results should have size 1
        results.head.origin shouldBe Some(invokeId)
      }
    }

    "stamp origin = invokeId on the Tool-role Failure Message a Failure tool produces" in {
      runWith(
        provider = new StubProvider(FailureTool.name.value, "failure-call"),
        tools    = Vector(FailureTool),
        suffix   = "failure"
      ).map { signals =>
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        invokes should have size 1
        val invokeId = invokes.head._id

        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        toolMessages.head.disposition shouldBe a [MessageDisposition.Failure]
        toolMessages.head.origin shouldBe Some(invokeId)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
