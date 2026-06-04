package sigil.provider.wire

import fabric.{Null, arr, num, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.{ProviderStreamException, ToolCallAccumulator}

/**
 * Regression for sigil bug #193 — OpenAI-compatible upstream gateways
 * signal mid-stream errors via SSE chunks where `choices` is empty
 * and a top-level `error` object carries the diagnostic. The parser
 * already has an inline-error throw path for this exact shape — but
 * it's gated on `Config.inlineErrorThrows` which defaults to `false`,
 * so most providers (OpenRouter, DeepInfra, DigitalOcean, DeepSeek)
 * silently dropped these chunks. The orchestrator then saw a stream
 * with no content / tool_calls and fell through to the empty-completion
 * placeholder, attributing the failure to the model when the actual
 * cause was an upstream timeout / 502.
 *
 * Wire evidence (Sage, 2026-05-16 — kimi-k2.6 via OpenRouter's Io Net
 * upstream timing out mid-reasoning):
 *
 * ```
 * { "id": "gen-...", "choices": [],
 *   "error": { "code": 502, "message": "Upstream idle timeout exceeded",
 *              "metadata": {"error_type": "provider_timeout", ...} } }
 * ```
 *
 * The fix flips the default to `true` so every OpenAI-compat provider
 * surfaces upstream errors as typed `ProviderStreamException`s that
 * the agent loop's failure-handler can render as a Failure Message.
 */
class OpenAIChatCompletionsErrorChunkSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  spec.TestSigil.initFor(getClass.getSimpleName)

  private def freshState = new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(Vector.empty))

  "OpenAIChatCompletions.parseChunk on a mid-stream error chunk (sigil bug #193)" should {

    "throw ProviderStreamException for the OpenRouter Io Net upstream-timeout shape (default config)" in {
      // Default config — historically `inlineErrorThrows = false`,
      // which silently dropped these chunks. Bug #193 flips the
      // default so this throws by default for every OpenAI-compat
      // provider.
      val defaultConfig = OpenAIChatCompletions.Config(
        providerNamespace = "openrouter",
        providerName = "OpenRouter"
      )
      val chunk = obj(
        "id" -> str("gen-1778934609-yxK1UHkKR86HNGVjs6l2"),
        "object" -> str("chat.completion.chunk"),
        "created" -> num(1778934609),
        "model" -> str("moonshotai/kimi-k2.6-20260420"),
        "provider" -> str("Io Net"),
        "choices" -> arr(),
        "error" -> obj(
          "code" -> num(502),
          "message" -> str("Upstream idle timeout exceeded"),
          "metadata" -> obj(
            "error_type" -> str("provider_timeout"),
            "provider_name" -> str("Io Net")
          )
        )
      )
      val thrown = intercept[ProviderStreamException] {
        OpenAIChatCompletions.parseChunk(chunk, freshState, defaultConfig)
      }
      thrown.code shouldBe 502
      thrown.getMessage should include("Upstream idle timeout exceeded")
      rapid.Task.pure(succeed)
    }

    "stay silent when inlineErrorThrows is explicitly opted out" in {
      // Apps that genuinely want the old silent-drop behaviour can
      // pass `inlineErrorThrows = false` explicitly.
      val optedOut = OpenAIChatCompletions.Config(
        providerNamespace = "custom",
        providerName = "Custom",
        inlineErrorThrows = false
      )
      val chunk = obj(
        "choices" -> arr(),
        "error" -> obj(
          "code" -> num(502),
          "message" -> str("Upstream idle timeout exceeded")
        )
      )
      noException should be thrownBy OpenAIChatCompletions.parseChunk(chunk, freshState, optedOut)
      rapid.Task.pure(succeed)
    }

    "tolerate `error: null` (no throw, no events)" in {
      val cfg = OpenAIChatCompletions.Config(
        providerNamespace = "openrouter",
        providerName = "OpenRouter"
      )
      val chunk = obj(
        "choices" -> arr(),
        "error" -> Null
      )
      val events = OpenAIChatCompletions.parseChunk(chunk, freshState, cfg)
      events shouldBe empty
      rapid.Task.pure(succeed)
    }

    "still parse normal-shape chunks alongside the error path being enabled by default" in {
      val cfg = OpenAIChatCompletions.Config(
        providerNamespace = "openrouter",
        providerName = "OpenRouter"
      )
      val chunk = obj(
        "choices" -> arr(obj(
          "delta" -> obj(
            "content" -> str("hello")
          ),
          "finish_reason" -> Null,
          "index" -> num(0)
        ))
      )
      noException should be thrownBy OpenAIChatCompletions.parseChunk(chunk, freshState, cfg)
      rapid.Task.pure(succeed)
    }
  }
}
