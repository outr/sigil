package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{ConversationView, Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message, MessageRole, ToolInvoke, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId,
  ConversationMode,
  ConversationRequest,
  GenerationSettings,
  Instructions,
  Provider,
  ProviderCall,
  ProviderEvent,
  ProviderType,
  StopReason
}
import sigil.signal.{EventState, Signal, ToolDelta}
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolGates,
  ToolIO,
  ToolInput,
  ToolName,
  ToolPrecondition,
  ToolPreconditionResult,
  ToolProfile,
  ToolResult,
  ToolSpec
}
import sigil.tool.ToolContext
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
    type Input = NoResponseInput
    type Output = TextToolOutput
    val io: ToolIO[NoResponseInput, TextToolOutput] = ToolIO.derived[NoResponseInput, TextToolOutput]
    override val name: ToolName = ToolName("gate_satisfied")
    override val description: String = "tool whose preconditions pass"
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(
        effect = Effect.Mutating(MutationTargeting.none),
        gates = ToolGates(preconditions = List(
          StaticPrecondition("ok-1", ToolPreconditionResult.Satisfied),
          StaticPrecondition("ok-2", ToolPreconditionResult.Satisfied)
        ))
      ),
      discovery = DiscoverySpec(keywords = Set("test", "gate"))
    )
    override def _id: Id[Tool] = Id[Tool](name.value)
    protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

    private def executeResult(input: NoResponseInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("RAN")))
  }

  private object BlockedTool extends Tool {
    type Input = NoResponseInput
    type Output = TextToolOutput
    val io: ToolIO[NoResponseInput, TextToolOutput] = ToolIO.derived[NoResponseInput, TextToolOutput]
    override val name: ToolName = ToolName("gate_blocked")
    override val description: String = "tool with one unsatisfied precondition"
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(
        effect = Effect.Mutating(MutationTargeting.none),
        gates = ToolGates(preconditions = List(
          StaticPrecondition("oauth", ToolPreconditionResult.Satisfied),
          StaticPrecondition("rate-limit", ToolPreconditionResult.Unsatisfied("daily quota exceeded", suggestedFix = Some("upgrade_plan")))
        ))
      ),
      discovery = DiscoverySpec(keywords = Set("test", "gate"))
    )
    override def _id: Id[Tool] = Id[Tool](name.value)
    protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

    private def executeResult(input: NoResponseInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("SHOULD_NOT_RUN")))
  }

  private class StubProvider(tool: Tool { type Input = NoResponseInput }, callIdValue: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId(callIdValue)
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, tool.name.value),
        ProviderEvent.toolCall(cid, tool)(NoResponseInput()),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private def runWith(provider: Provider, tools: Vector[Tool], suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"precondition-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(Model.id("test", "model")),
      instructions = Instructions(),
      turnInput = TurnInput(ConversationView(conversationId = convId)),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      chain = List(TestUser, TestAgent),
      tools = tools
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Orchestrator + Tool.preconditions" should {
    "let a tool run when all preconditions return Satisfied" in
      runWith(new StubProvider(SatisfiedTool, "ok-call"), Vector(SatisfiedTool), "ok").map { signals =>
        // A satisfied tool runs and settles its ToolInvoke via a
        // ToolDelta carrying the typed payload — no precondition-
        // blocked Failure Message.
        val toolMsgs = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
        toolMsgs shouldBe empty
        val results = signals.collect {
          case d: ToolDelta if d.outcome.contains(ToolOutcome.Success) => d
        }
        results should have size 1
        results.head.output.collect { case TextToolOutput(t) => t } shouldBe Some("RAN")
      }

    "block tool execution when any precondition returns Unsatisfied — body not invoked" in
      runWith(new StubProvider(BlockedTool, "block-call"), Vector(BlockedTool), "block").map { signals =>
        val toolMsgs = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
        toolMsgs should have size 1
        // BlockedTool's `execute` would emit `Text("SHOULD_NOT_RUN")` — verify it didn't run.
        // The blocked-precondition Message is a Failure disposition; its
        // content carries the diagnostic, not SHOULD_NOT_RUN.
        val texts = toolMsgs.head.content.collect { case ResponseContent.Text(t) => t }
        texts shouldNot contain("SHOULD_NOT_RUN")
        toolMsgs.head.isFailure shouldBe true
      }

    "emit a Failure-disposition Message describing the blocked precondition + suggestedFix" in
      runWith(new StubProvider(BlockedTool, "block-fail-call"), Vector(BlockedTool), "block-fail").map { signals =>
        val toolMsgs = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
        toolMsgs.head.isFailure shouldBe true
        val body = toolMsgs.head.failureReason.getOrElse("")
        body should include("rate-limit")
        body should include("daily quota exceeded")
        body should include("upgrade_plan")
        toolMsgs.head.disposition match {
          case sigil.event.MessageDisposition.Failure(rec, _) => rec shouldBe true
          case _ => fail("expected Failure disposition")
        }
      }

    "stamp the originating ToolInvoke id on the blocked Message (frame-pairing invariant)" in
      runWith(new StubProvider(BlockedTool, "block-stamp-call"), Vector(BlockedTool), "block-stamp").map { signals =>
        val invokes = signals.collect { case ti: ToolInvoke => ti }
        invokes should have size 1
        val invokeId = invokes.head._id
        val toolMsgs = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
        toolMsgs.head.origin shouldBe Some(invokeId)
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
