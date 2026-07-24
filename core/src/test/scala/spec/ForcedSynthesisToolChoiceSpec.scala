package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.provider.{
  BuiltInTool, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason, ToolChoice
}
import sigil.tool.core.{CoreTools, NoResponseTool, RespondOptionsTool, RespondTool}
import spice.http.HttpRequest

/**
 * Sigil #375 — the forced-synthesis recovery turn must pin
 * `tool_choice` to the specific terminal `respond` tool, not the
 * weaker `Required` (Anthropic `{type:"any"}`), and must drop built-in
 * server tools (`web_search`, …) for that turn. Under `Required` a
 * tool-saturated model emits a tool OUTSIDE the narrowed respond
 * roster (observed Opus 4.8 answering a respond-only turn with
 * browser_screenshot), the recovery check fails, and the loop throws
 * `AgentRunawayException`.
 *
 * Drives the real `Provider.apply` → `translate` → `call` path with a
 * capturing provider so the assertion is on the actual translated
 * [[ProviderCall]], not a reconstruction.
 */
class ForcedSynthesisToolChoiceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  /**
   * Records the `ProviderCall` that reaches the wire and emits a
   * trivial terminal so `apply` completes.
   */
  private class CapturingProvider extends Provider {
    @volatile var captured: Option[ProviderCall] = None
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      captured = Some(input)
      Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
    }
  }

  private def translatedCall(forceSynth: Boolean): Task[ProviderCall] = {
    val convId = Conversation.id("forced-synth-toolchoice")
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
      // Respond family survives the forced-synthesis roster filter;
      // a built-in server tool that must NOT survive it.
      tools = Vector(RespondTool, RespondOptionsTool, NoResponseTool),
      builtInTools = Set(BuiltInTool.WebSearch),
      forceResponseSynthesis = forceSynth
    )
    val provider = new CapturingProvider
    provider.apply(request).toList.map(_ =>
      provider.captured.getOrElse(fail("provider.call was never invoked")))
  }

  "Forced-synthesis tool_choice (sigil #375)" should {

    "pin tool_choice to Specific(respond) and drop built-in tools on the recovery turn" in
      translatedCall(forceSynth = true).map { pc =>
        pc.toolChoice shouldBe ToolChoice.Specific(RespondTool.schema.name)
        pc.builtInTools shouldBe empty
      }

    "leave tool_choice Required and keep built-in tools on a normal turn" in
      translatedCall(forceSynth = false).map { pc =>
        pc.toolChoice shouldBe ToolChoice.Required
        pc.builtInTools should contain(BuiltInTool.WebSearch)
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
