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
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.{TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult, WireCall}
import spice.http.HttpRequest

/**
 * The accumulator and the orchestrator resolve tool names against ONE
 * roster — [[ConversationRequest.roster]], threaded into the wire path as
 * the same instance via [[sigil.provider.ProviderCall.roster]]. An
 * out-of-roster call arrives as `WireCall.Unresolved` and lands on
 * [[sigil.tool.core.UnknownTool]] — a recoverable Tool-role Failure
 * Message paired to the invoke — while an in-roster Unresolved (replayed
 * fixture, shed wire roster) rebinds to the real tool and executes.
 */
class EffectiveToolRosterSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class ReadFileInput(path: String) extends ToolInput derives RW
  case class ReadFileOutput(content: String) extends sigil.tool.ToolOutput derives RW

  private object ReadFileTool extends Tool {
    type Input  = ReadFileInput
    type Output = ReadFileOutput
    val inputRW  = summon[RW[ReadFileInput]]
    val outputRW = summon[RW[ReadFileOutput]]
    val name        = ToolName("read_file")
    val description = "Read a file by path."
    override def executeResult(input: ReadFileInput, context: ToolContext): Task[ToolResult[ReadFileOutput]] =
      Task.pure(ToolResult.Success(ReadFileOutput(s"contents of ${input.path}")))
  }

  private val modelId: Id[Model] = Model.id("test", "model")

  /** Provider that emits a `read_file` call the accumulator could not
    * resolve — the `WireCall.Unresolved` shape a wire roster narrowed by
    * forced synthesis produces when the model calls outside it. */
  private class OutOfRosterProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      val cid = CallId("c-out-of-roster")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, "read_file"),
        ProviderEvent.ToolCallComplete(cid,
          WireCall.Unresolved("read_file", fabric.obj("path" -> fabric.str("sections/main.liquid")))),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def requestFor(convId: Id[sigil.conversation.Conversation], forceSynth: Boolean): ConversationRequest =
    ConversationRequest(
      conversationId         = convId,
      model                = TestSigil.testModel(modelId),
      instructions           = Instructions(),
      turnInput              = TurnInput(conversationId = convId),
      currentMode            = ConversationMode,
      currentTopic           = TestTopicEntry,
      previousTopics         = Nil,
      generationSettings     = GenerationSettings(maxOutputTokens = Some(50)),
      chain                  = List(TestUser, TestAgent),
      tools                  = Vector(ReadFileTool),
      forceResponseSynthesis = forceSynth
    )

  private def runWith(forceSynth: Boolean, suffix: String): Task[List[Signal]] = {
    val convId = Conversation.id(s"effective-roster-$suffix")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = requestFor(convId, forceSynth)
    for {
      _       <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new OutOfRosterProvider, request, conv).toList
    } yield signals
  }

  "Effective tool roster (sigil #274)" should {

    "dispatch to UnknownTool when forced-synthesis strips the wire roster" in {
      // With forced-synthesis, the effective roster is the respond family
      // only. `read_file` is out-of-scope; the Unresolved call cannot
      // rebind against `request.roster` and routes to UnknownTool, which
      // emits a paired Tool-role Failure Message.
      runWith(forceSynth = true, "force").map { signals =>
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        val msg = toolMessages.head
        msg.disposition shouldBe a [MessageDisposition.Failure]
        msg.failureReason.getOrElse("") should include ("Unknown tool")
        msg.failureReason.getOrElse("") should include ("read_file")
        msg.origin shouldBe defined
      }
    }

    "rebind an Unresolved call through the request roster when the tool is in scope" in {
      // Without forced synthesis `read_file` IS in `request.roster`; the
      // same Unresolved call rebinds to the real tool, decodes, and
      // executes — the accumulator and orchestrator resolve against one
      // roster, so a decodable name can never fall through to the
      // unknown-tool refusal.
      runWith(forceSynth = false, "rebind").map { signals =>
        val settles = signals.collect {
          case d: sigil.signal.ToolDelta if d.outcome.contains(sigil.event.ToolOutcome.Success) => d
        }
        settles should not be empty
        signals.collect { case m: Message if m.role == MessageRole.Tool => m } shouldBe empty
      }
    }

    "hand the accumulator's ProviderCall the same roster instance the orchestrator dispatches against" in {
      import sigil.testkit.MockProvider
      val convId = Conversation.id("effective-roster-instance")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = requestFor(convId, forceSynth = true)
      val mock = MockProvider(TestSigil, responses = List(MockProvider.Script.text("ok")))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- Orchestrator.process(TestSigil, mock, request, conv).toList
      } yield {
        val call = mock.lastCall.getOrElse(fail("MockProvider captured no call"))
        (call.roster eq request.roster) shouldBe true
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
