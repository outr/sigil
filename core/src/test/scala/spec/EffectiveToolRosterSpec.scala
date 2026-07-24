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
import sigil.tool.{JsonInput, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult}
import spice.http.HttpRequest

/**
 * Sigil bug #274 — the orchestrator's `toolsByName` and the accumulator's
 * `tools` must agree on what's in scope, otherwise the model can emit a
 * `tool_use` for a tool the accumulator's parser doesn't know (so it falls
 * back to `JsonInput`), the orchestrator's `toolsByName` finds the real
 * typed tool, and dispatch `asInstanceOf`s the JsonInput to the tool's
 * typed `Input` → ClassCastException.
 *
 * The fix routes both consumers through [[ConversationRequest.effectiveTools]],
 * which applies the forced-synthesis filter. Out-of-roster calls now land
 * on [[sigil.tool.core.UnknownTool]] — same shape as a genuinely-unknown
 * tool name — with a recoverable Tool-role Failure Message paired to the
 * invoke so the agent retries with a respond on the next iteration.
 */
class EffectiveToolRosterSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  case class ReadFileInput(path: String) extends ToolInput derives RW
  case class ReadFileOutput(content: String) extends sigil.tool.ToolOutput derives RW

  private object ReadFileTool extends Tool {
    type Input = ReadFileInput
    type Output = ReadFileOutput
    val inputRW = summon[RW[ReadFileInput]]
    val outputRW = summon[RW[ReadFileOutput]]
    val name = ToolName("read_file")
    val description = "Read a file by path."
    override def executeResult(input: ReadFileInput, context: ToolContext): Task[ToolResult[ReadFileOutput]] =
      Task.pure(ToolResult.Success(ReadFileOutput(s"contents of ${input.path}")))
  }

  private val modelId: Id[Model] = Model.id("test", "model")

  /**
   * Provider that emits a typed `read_file` call with a `JsonInput`
   * payload — simulates the accumulator's `case None =>` path (sigil
   * #271) when the model invokes a tool the wire-sent roster doesn't
   * include but the framework otherwise knows about.
   */
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
        ProviderEvent.ToolCallComplete(cid, JsonInput(fabric.obj("path" -> fabric.str("sections/main.liquid")))),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def runWith(forceSynth: Boolean): Task[List[Signal]] = {
    val convId = Conversation.id(s"effective-roster-force")
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
      tools = Vector(ReadFileTool),
      forceResponseSynthesis = forceSynth
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      signals <- Orchestrator.process(TestSigil, new OutOfRosterProvider, request, conv).toList
    } yield signals
  }

  "Effective tool roster (sigil #274)" should {

    "dispatch to UnknownTool when forced-synthesis strips the wire roster" in
      // With forced-synthesis, the wire roster is the respond family
      // only. `read_file` is out-of-scope; the orchestrator's
      // `toolsByName` (sourced from `request.effectiveTools`) doesn't
      // contain it; the JsonInput-bearing ToolCallComplete routes to
      // UnknownTool, which emits a paired Tool-role Failure Message.
      runWith(forceSynth = true).map { signals =>
        val toolMessages = signals.collect {
          case m: Message if m.role == MessageRole.Tool => m
        }
        toolMessages should have size 1
        val msg = toolMessages.head
        msg.disposition shouldBe a[MessageDisposition.Failure]
        msg.failureReason.getOrElse("") should include("Unknown tool")
        msg.failureReason.getOrElse("") should include("read_file")
        msg.origin shouldBe defined
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
