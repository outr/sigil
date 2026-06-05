package spec

import fabric.{Json, Null, arr, num, obj, str}
import fabric.io.JsonFormatter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ProviderStreamException, ToolCallAccumulator}
import sigil.provider.wire.OpenAIChatCompletions

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

  private val cfg: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace        = "cloudflare",
    providerName             = "Cloudflare",
    nonStrictSchemaTransform = identity,
    emptyBudgetBurnThrows    = true
  )

  private def freshState(): OpenAIChatCompletions.StreamState =
    new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(Vector.empty))

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
      val state = new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(Vector.empty))
      OpenAIChatCompletions.parseLine("data: " + JsonFormatter.Compact(reasoningChunk), state, lenientCfg)
      noException should be thrownBy state.closeStream(lenientCfg)
    }
  }
}
