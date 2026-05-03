package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.conversation.{Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.{EventState, Signal}
import sigil.tool.{Tool, ToolInput, ToolName, ToolPrecondition, ToolPreconditionResult}
import sigil.tool.model.{NoResponseInput, ResponseContent}
import spice.http.HttpRequest
import fabric.rw.*

/**
 * Coverage for the [[ToolPrecondition]] gate. The orchestrator's
 * `executeAtomic` runs every tool's preconditions before invoking
 * `tool.execute`. When all checks return `Satisfied`, the tool runs
 * normally. When any returns `Unsatisfied`, the tool's body is NOT
 * invoked — instead a `Role.Tool` Message containing a
 * `Failure(recoverable = true)` block is emitted so the agent reads
 * the blocking reason on its next turn.
 *
 * Test shape mirrors [[OrchestratorOriginStampingSpec]] — drive a
 * stub provider that asks the orchestrator to call a synthetic tool,
 * vary the tool's preconditions, and assert what arrives in the
 * Signal stream.
 */
class ToolPreconditionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private case class StaticPrecondition(name: String, result: ToolPreconditionResult) extends ToolPrecondition {
    override def check(context: TurnContext): Task[ToolPreconditionResult] = Task.pure(result)
  }

  private object SatisfiedTool extends Tool {
    override val name: ToolName = ToolName("gate_satisfied")
    override def description: String = "tool whose preconditions pass"
    override def inputRW: RW[? <: ToolInput] = summon[RW[NoResponseInput]]
    override def space: SpaceId = GlobalSpace
    override def preconditions: List[ToolPrecondition] = List(
      StaticPrecondition("ok-1", ToolPreconditionResult.Satisfied),
      StaticPrecondition("ok-2", ToolPreconditionResult.Satisfied)
    )
    override def execute(input: ToolInput, context: TurnContext): Stream[Event] =
      Stream.emit(Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text("RAN")),
        state = EventState.Complete,
        role = MessageRole.Tool
      ))
    override def _id: Id[Tool] = Id[Tool](name.value)
  }

  private object BlockedTool extends Tool {
    override val name: ToolName = ToolName("gate_blocked")
    override def description: String = "tool with one unsatisfied precondition"
    override def inputRW: RW[? <: ToolInput] = summon[RW[NoResponseInput]]
    override def space: SpaceId = GlobalSpace
    override def preconditions: List[ToolPrecondition] = List(
      StaticPrecondition("oauth", ToolPreconditionResult.Satisfied),
      StaticPrecondition("rate-limit", ToolPreconditionResult.Unsatisfied("daily quota exceeded", suggestedFix = Some("upgrade_plan")))
    )
    override def execute(input: ToolInput, context: TurnContext): Stream[Event] =
      Stream.emit(Message(
        participantId = context.caller,
        conversationId = context.conversation.id,
        topicId = context.conversation.currentTopicId,
        content = Vector(ResponseContent.Text("SHOULD_NOT_RUN")),
        state = EventState.Complete,
        role = MessageRole.Tool
      ))
    override def _id: Id[Tool] = Id[Tool](name.value)
  }

  private class StubProvider(toolName: String, callIdValue: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
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
    val convId = Conversation.id(s"precondition-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId     = convId,
      modelId            = Model.id("test", "model"),
      instructions       = Instructions(),
      turnInput          = TurnInput(ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))),
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

  "Orchestrator + Tool.preconditions" should {
    "let a tool run when all preconditions return Satisfied" in {
      runWith(new StubProvider(SatisfiedTool.name.value, "ok-call"), Vector(SatisfiedTool), "ok").map { signals =>
        val toolMsgs = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
        toolMsgs should have size 1
        val texts = toolMsgs.head.content.collect { case ResponseContent.Text(t) => t }
        texts shouldBe Vector("RAN")
      }
    }

    "block tool execution when any precondition returns Unsatisfied — body not invoked" in {
      runWith(new StubProvider(BlockedTool.name.value, "block-call"), Vector(BlockedTool), "block").map { signals =>
        val toolMsgs = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
        toolMsgs should have size 1
        // BlockedTool's `execute` would emit `Text("SHOULD_NOT_RUN")` — verify it didn't run.
        val texts = toolMsgs.head.content.collect { case ResponseContent.Text(t) => t }
        texts shouldBe empty
      }
    }

    "emit a Failure block describing the blocked precondition + suggestedFix" in {
      runWith(new StubProvider(BlockedTool.name.value, "block-fail-call"), Vector(BlockedTool), "block-fail").map { signals =>
        val toolMsgs = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
        val failures = toolMsgs.head.content.collect { case f: ResponseContent.Failure => f }
        failures should have size 1
        val body = failures.head.reason
        body should include("rate-limit")
        body should include("daily quota exceeded")
        body should include("upgrade_plan")
        failures.head.recoverable shouldBe true
      }
    }

    "stamp the originating ToolInvoke id on the blocked Message (frame-pairing invariant)" in {
      runWith(new StubProvider(BlockedTool.name.value, "block-stamp-call"), Vector(BlockedTool), "block-stamp").map { signals =>
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        invokes should have size 1
        val invokeId = invokes.head._id
        val toolMsgs = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
        toolMsgs.head.origin shouldBe Some(invokeId)
      }
    }
  }
}
