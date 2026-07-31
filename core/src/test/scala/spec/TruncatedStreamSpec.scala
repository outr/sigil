package spec

import fabric.{Json, Null, arr, num, obj, str}
import fabric.io.JsonFormatter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.db.Model
import sigil.provider.{
  GenerationSettings, ProviderCall, ProviderStreamException, ReasoningMode, ToolCallAccumulator, ToolChoice
}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.tool.ToolRoster

/**
 * Sigil #360 — a chat-completions stream that closes mid-flight with no
 * `[DONE]` marker, no `finish_reason`, and only `reasoning_content`
 * (`hasUsefulOutput == false`) bypassed every empty-turn detector
 * (`inlineErrorThrows`, the `length`/`stop` empty paths, `flushDone`,
 * which only runs on `[DONE]`) and silently produced an empty assistant
 * turn — no message, no error, no retry. `closeStream`, run at
 * connection-close, now fails loudly so the agent loop surfaces a
 * Failure and `ProviderStrategy` can retry.
 *
 * Drives the wire object's `parseLine` / `closeStream` directly; no HTTP.
 */
class TruncatedStreamSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val cfg: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace        = "cloudflare",
    providerName             = "Cloudflare",
    schemaDialect = sigil.provider.SchemaDialect.Identity,
    emptyBudgetBurnThrows    = true
  )

  private def freshState(): OpenAIChatCompletions.StreamState =
    new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(ToolRoster.empty))

  private def feed(state: OpenAIChatCompletions.StreamState, json: Json): Unit =
    OpenAIChatCompletions.parseLine("data: " + JsonFormatter.Compact(json), state, cfg)

  /** The bug's final chunk: reasoning only, finish_reason null. */
  private val reasoningChunk: Json = obj(
    "choices" -> arr(obj(
      "delta"         -> obj("content" -> Null, "reasoning_content" -> str(" workflow."), "tool_calls" -> Null),
      "finish_reason" -> Null,
      "index"         -> num(0)
    ))
  )

  private val contentChunk: Json = obj(
    "choices" -> arr(obj("delta" -> obj("content" -> str("hello")), "index" -> num(0)))
  )

  "OpenAIChatCompletions.closeStream (sigil #360)" should {

    "throw `truncated_stream` when the stream closes with only reasoning and no [DONE]/finish_reason" in {
      val state = freshState()
      feed(state, reasoningChunk)
      val thrown = intercept[ProviderStreamException](state.closeStream(cfg))
      thrown.typ shouldBe "truncated_stream"
      thrown.code shouldBe 200
      thrown.providerKey shouldBe "cloudflare"
      thrown.getMessage.toLowerCase should include("truncated")
    }

    "not throw when the stream terminated normally with [DONE]" in {
      val state = freshState()
      feed(state, reasoningChunk)
      OpenAIChatCompletions.parseLine("data: [DONE]", state, cfg)
      noException should be thrownBy state.closeStream(cfg)
    }

    "not throw when the stream emitted useful content (not a silent-empty case)" in {
      val state = freshState()
      feed(state, contentChunk)
      noException should be thrownBy state.closeStream(cfg)
      state.closeStream(cfg) shouldBe empty
    }

    "stay silent for providers that don't opt into empty-turn throws" in {
      val lenientCfg = cfg.copy(emptyBudgetBurnThrows = false)
      val state = new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(ToolRoster.empty))
      OpenAIChatCompletions.parseLine("data: " + JsonFormatter.Compact(reasoningChunk), state, lenientCfg)
      noException should be thrownBy state.closeStream(lenientCfg)
    }
  }

  "OpenAIChatCompletions.isReasoningRequest (sigil #360 — idle-timeout gate)" should {
    val model = TestSigil.testModel(Model.id("test", "reasoner-360"))
    def call(mode: ReasoningMode): ProviderCall =
      ProviderCall(
        model              = model,
        system             = "",
        messages           = Vector.empty,
        roster = ToolRoster(Vector.empty),
        builtInTools       = Set.empty,
        toolChoice         = ToolChoice.None,
        generationSettings = GenerationSettings(reasoningMode = mode)
      )
    val reasoningCfg = cfg.copy(reasoningPolicy = OpenAIChatCompletions.ReasoningPolicy.ChatTemplateEnableThinking)

    "be true when a reasoning policy is active and reasoning isn't Off (On / Auto)" in {
      OpenAIChatCompletions.isReasoningRequest(call(ReasoningMode.On), reasoningCfg)   shouldBe true
      OpenAIChatCompletions.isReasoningRequest(call(ReasoningMode.Auto), reasoningCfg) shouldBe true
    }

    "be false when reasoning is explicitly Off" in {
      OpenAIChatCompletions.isReasoningRequest(call(ReasoningMode.Off), reasoningCfg) shouldBe false
    }

    "be false when the provider forwards no reasoning policy" in {
      // cfg's reasoningPolicy defaults to None.
      OpenAIChatCompletions.isReasoningRequest(call(ReasoningMode.On), cfg) shouldBe false
    }
  }
}
