package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.TurnContext
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageDisposition, MessageRole}
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
import sigil.signal.Signal
import sigil.signal.ToolDelta
import sigil.event.ToolOutcome
import sigil.tool.{
  DiscoverySpec,
  Effect,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolInput,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}
import sigil.tool.ToolContext
import spice.http.HttpRequest
import sigil.tool.WireCall

/**
 * Coverage for the call ↔ output pairing invariant — every dispatched
 * `ToolCallComplete` settles its invoke with a Failure outcome the agent
 * can act on, regardless of whether the name resolves or the tool body
 * returned a logical failure.
 *
 *   1. Tool name not in the agent's roster (sigil #167 / #271) — the
 *      orchestrator falls back to [[sigil.tool.core.UnknownTool]] which
 *      emits a paired Tool-role Failure Message (origin = invokeId)
 *      that re-triggers the agent loop. Same shape as any other tool
 *      dispatch — no special case in the orchestrator.
 *
 *   2. Registered tool whose `executeResult` returns
 *      [[sigil.tool.ToolResult.Failure]] — the framework settles the
 *      invoke with a [[ToolDelta]] folding `outcome = Failure`. The
 *      agent reads the failure through the settled invoke's outcome
 *      field on the next turn's context.
 */
class OrchestratorUnpairedToolCallSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class EmptyToolInput() extends ToolInput derives RW

  /**
   * Tool whose resolution is a logical failure — simulates a tool
   * that ran but couldn't produce a useful result. The framework
   * builds the paired Tool-role Failure Message from the returned
   * [[ToolResult.Failure]].
   */
  private object SilentTool extends Tool {
    type Input = EmptyToolInput
    type Output = TextToolOutput
    val io: ToolIO[EmptyToolInput, TextToolOutput] = ToolIO.derived[EmptyToolInput, TextToolOutput]
    override val name = ToolName("silent_tool")
    override val description = "Returns nothing useful."
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "silent"))
    )
    protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

    private def executeResult(input: EmptyToolInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.failure("Tool 'silent_tool' failed internally — it produced no usable result."))
  }

  private val modelId: Id[Model] = Model.id("test", "model")

  /**
   * Provider that emits ToolCallComplete for whatever toolName the
   * test asks for. `wireCall` lets each scenario inject the matching
   * resolution shape — `WireCall.Unresolved` for unknown names (what the
   * real accumulator produces) or a decoded call for a registered tool
   * in the silent-failure scenario.
   */
  private class FakeProvider(toolName: String, wireCall: WireCall) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("c1")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, toolName),
        ProviderEvent.ToolCallComplete(cid, wireCall),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def runWith(provider: Provider, tools: Vector[Tool], suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"unpaired-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId = convId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      previousTopics = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      chain = List(TestUser, TestAgent),
      tools = tools
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, provider, request, conv).toList
    } yield signals
  }

  "Orchestrator for an unknown tool name (sigil #167 / #271)" should {

    "emit a Tool-role Failure Message paired to the invoke via UnknownTool" in
      runWith(
        new FakeProvider("not_a_real_tool", WireCall.Unresolved("not_a_real_tool", fabric.Obj.empty)),
        tools = Vector.empty,
        "unknown").map { signals =>
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        val msg = toolMessages.head
        msg.disposition shouldBe a[MessageDisposition.Failure]
        msg.failureReason.getOrElse("") should include("Unknown tool")
        msg.failureReason.getOrElse("") should include("not_a_real_tool")
        msg.origin shouldBe defined
      }
  }

  "Orchestrator for a registered tool that returns a logical failure" should {

    "settle the invoke with a ToolDelta carrying outcome = Failure" in
      runWith(new FakeProvider("silent_tool", WireCall.decoded(SilentTool)(EmptyToolInput())), tools = Vector(SilentTool), "silent").map {
        signals =>
          val failureDeltas = signals.collect {
            case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) => d
          }
          failureDeltas should not be empty
          val reason = failureDeltas.head.outcome.collect {
            case f: ToolOutcome.Failure => f.reason
          }.getOrElse("")
          reason should include("failed internally")
          reason should include("silent_tool")
      }
  }

  /**
   * Provider that opens a tool call then mid-stream errors out — the
   * shape parse failures (sigil #272) and pre-flight errors take.
   */
  private class ErrorProvider(toolName: String, errorMsg: String) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("c1")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, toolName),
        ProviderEvent.Error(errorMsg),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  "Orchestrator on a ProviderEvent.Error mid-tool-call (sigil #273)" should {

    "emit a paired Tool-role Failure Message so the agent loop re-triggers" in
      // Pre-fix the Error branch settled the orphan invoke with a
      // ToolDelta but emitted no Tool-role event. `TriggerFilter` re-
      // fires on `role == MessageRole.Tool`, so the agent loop saw no
      // new trigger and treated the turn as "no tool call" — burning
      // its recovery budget on a forced-synthesis attempt that knew
      // nothing about the failure. The fix pairs the orphan settle
      // with a Tool-role Failure Message carrying the diagnostic so
      // the model reads it next turn and self-corrects.
      runWith(
        new ErrorProvider("create_page", "Failed to parse args for tool create_page: bad shape"),
        tools = Vector.empty,
        "parsefail").map { signals =>
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        val msg = toolMessages.head
        msg.disposition shouldBe a[MessageDisposition.Failure]
        msg.failureReason.getOrElse("") should include("Provider error")
        msg.failureReason.getOrElse("") should include("Failed to parse args")
        msg.origin shouldBe defined
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
