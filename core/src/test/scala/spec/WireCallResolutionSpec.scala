package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageDisposition, MessageRole, ToolOutcome}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason, ToolCallAccumulator
}
import sigil.signal.{Signal, ToolDelta}
import sigil.tool.core.RespondTool
import sigil.tool.{ToolRoster, WireCall}
import spice.http.HttpRequest

/**
 * Coverage for the accumulator's roster-first resolution and the
 * orchestrator's `WireCall` consumption:
 *
 *   1. A tool name absent from the roster produces `WireCall.Unresolved`
 *      carrying the model's args verbatim — parsed JSON when parseable,
 *      the raw text as a `Str` when not. Nothing is discarded.
 *
 *   2. The orchestrator's refusal for an unresolved call preserves those
 *      args so the agent can carry them to the corrected call.
 */
class WireCallResolutionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  private def accumulate(toolName: String, args: String): WireCall = {
    val acc = new ToolCallAccumulator(ToolRoster(Vector(RespondTool)), providerKey = "test")
    acc.start(0, CallId("wc-1"), toolName)
    acc.appendArgs(0, args)
    acc.complete().collectFirst { case ProviderEvent.ToolCallComplete(_, wc) => wc }
      .getOrElse(fail("no ToolCallComplete emitted"))
  }

  private class UnresolvedCallProvider(wireCall: WireCall) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("wc-orch-1")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, wireCall.toolName),
        ProviderEvent.ToolCallComplete(cid, wireCall),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def runWith(wireCall: WireCall, suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"wirecall-$suffix")
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
      tools = Vector(RespondTool)
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new UnresolvedCallProvider(wireCall), request, conv).toList
    } yield signals
  }

  "ToolCallAccumulator resolution through the roster" should {

    "emit Unresolved with the parsed args for an unknown tool name" in Task {
      accumulate("imaginary_tool", """{"target":"prod","count":3}""") match {
        case WireCall.Unresolved(name, rawArgs) =>
          name shouldBe "imaginary_tool"
          rawArgs shouldBe fabric.obj("target" -> fabric.str("prod"), "count" -> fabric.num(3))
        case other => fail(s"expected Unresolved, got $other")
      }
    }

    "preserve unparseable args verbatim on an unknown tool name" in Task {
      val raw = """{"target": prod oops not json"""
      accumulate("imaginary_tool", raw) match {
        case WireCall.Unresolved(name, fabric.Str(text, _)) =>
          name shouldBe "imaginary_tool"
          text shouldBe raw
        case other => fail(s"expected Unresolved carrying the raw text, got $other")
      }
    }

    "emit Decoded packing the roster's tool for a resolvable call" in Task {
      accumulate(
        RespondTool.name.value,
        """{"topicLabel":"T","topicSummary":"t","content":"hi","endsTurn":true}""") match {
        case WireCall.Decoded(call) =>
          (call.tool eq RespondTool) shouldBe true
          call.inputFor(RespondTool).map(_.content) shouldBe Some("hi")
        case other => fail(s"expected Decoded, got $other")
      }
    }
  }

  "Orchestrator dispatch of an Unresolved call" should {

    "refuse with the model's exact args preserved on the invoke settle" in {
      val args = fabric.obj("target" -> fabric.str("prod"), "count" -> fabric.num(3))
      runWith(WireCall.Unresolved("imaginary_tool", args), "parsed-args").map { signals =>
        val toolMessages = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
        toolMessages should have size 1
        toolMessages.head.disposition shouldBe a[MessageDisposition.Failure]
        toolMessages.head.failureReason.getOrElse("") should include("imaginary_tool")
        val settle = signals.collectFirst {
          case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) => d
        }.getOrElse(fail("no failure settle delta"))
        val reason = settle.outcome.collect { case f: ToolOutcome.Failure => f.reason }.getOrElse("")
        reason should include("imaginary_tool")
        reason should include("prod")
        reason should include("3")
      }
    }

    "refuse with unparseable args preserved verbatim" in {
      val raw = """{"target": prod oops not json"""
      runWith(WireCall.Unresolved("imaginary_tool", fabric.Str(raw)), "raw-args").map { signals =>
        val settle = signals.collectFirst {
          case d: ToolDelta if d.outcome.exists(_.isInstanceOf[ToolOutcome.Failure]) => d
        }.getOrElse(fail("no failure settle delta"))
        val reason = settle.outcome.collect { case f: ToolOutcome.Failure => f.reason }.getOrElse("")
        reason should include(raw)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
