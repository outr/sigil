package sigil.provider.wire

import fabric.{Null, arr, num, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.{ProviderEvent, ToolCallAccumulator}

/**
 * Regression for sigil bug #192 — OpenRouter (and likely other
 * upstream gateways) streams reasoning fragments under `delta.reasoning`
 * rather than OpenAI's canonical `delta.reasoning_content`. The parser
 * recognized only the canonical field, silently dropping every
 * reasoning token from OpenRouter-routed reasoning models.
 *
 * Wire evidence (Sage, 2026-05-16 07:30 — kimi-k2.6 via OpenRouter's
 * Io Net upstream):
 *
 * ```
 * { "choices": [{ "delta": { "content": "", "reasoning": " The",
 *   "reasoning_details": [...] } }] }
 * ```
 *
 * Sigil saw `content == ""` and no `reasoning_content` → dropped the
 * reasoning entirely. Combined with bug #193 (mid-stream error chunks
 * also dropped), the user saw an empty-completion placeholder when
 * the model was actually reasoning + the upstream timed out.
 */
class OpenAIChatCompletionsReasoningFieldSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  spec.TestSigil.initFor(getClass.getSimpleName)

  private val config = OpenAIChatCompletions.Config(
    providerNamespace = "test",
    providerName      = "Test"
  )

  private def freshState = new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(Vector.empty))

  "OpenAIChatCompletions.parseChunk (sigil bug #192)" should {

    "emit a ThinkingDelta for OpenAI-canonical `reasoning_content` (existing behaviour)" in {
      val chunk = obj(
        "choices" -> arr(obj(
          "delta" -> obj(
            "content"           -> str(""),
            "reasoning_content" -> str(" hello")
          ),
          "finish_reason" -> Null,
          "index"         -> num(0)
        ))
      )
      val events = OpenAIChatCompletions.parseChunk(chunk, freshState, config)
      val thinking = events.collect { case t: ProviderEvent.ThinkingDelta => t.text }
      thinking shouldBe Vector(" hello")
      rapid.Task.pure(succeed)
    }

    "emit a ThinkingDelta for OpenRouter's `reasoning` field" in {
      // Verbatim wire-shape from the bug doc (kimi-k2.6 via Io Net).
      val chunk = obj(
        "choices" -> arr(obj(
          "delta" -> obj(
            "content"            -> str(""),
            "role"               -> str("assistant"),
            "reasoning"          -> str(" The"),
            "reasoning_details"  -> arr(obj(
              "type"   -> str("reasoning.text"),
              "text"   -> str(" The"),
              "format" -> str("unknown"),
              "index"  -> num(0)
            ))
          ),
          "finish_reason" -> Null,
          "index"         -> num(0)
        ))
      )
      val events = OpenAIChatCompletions.parseChunk(chunk, freshState, config)
      val thinking = events.collect { case t: ProviderEvent.ThinkingDelta => t.text }
      withClue(s"events: $events: ") {
        thinking shouldBe Vector(" The")
      }
      rapid.Task.pure(succeed)
    }

    "emit exactly one ThinkingDelta when BOTH `reasoning_content` AND `reasoning` are present" in {
      // Defensive: a provider could theoretically emit both. Prefer the
      // OpenAI-canonical field, don't double-emit.
      val chunk = obj(
        "choices" -> arr(obj(
          "delta" -> obj(
            "reasoning_content" -> str(" canonical"),
            "reasoning"         -> str(" router-flavored")
          ),
          "finish_reason" -> Null,
          "index"         -> num(0)
        ))
      )
      val events = OpenAIChatCompletions.parseChunk(chunk, freshState, config)
      val thinking = events.collect { case t: ProviderEvent.ThinkingDelta => t.text }
      thinking should have size 1
      thinking.head shouldBe " canonical"
      rapid.Task.pure(succeed)
    }

    "tolerate `reasoning: null` (parallel to existing reasoning_content null guard)" in {
      val chunk = obj(
        "choices" -> arr(obj(
          "delta" -> obj(
            "reasoning"         -> Null,
            "reasoning_content" -> Null
          ),
          "finish_reason" -> Null,
          "index"         -> num(0)
        ))
      )
      noException should be thrownBy OpenAIChatCompletions.parseChunk(chunk, freshState, config)
      rapid.Task.pure(succeed)
    }

    "accumulate reasoning fragments across N OpenRouter chunks (end-to-end)" in {
      val fragments = List(" The", " results", " seem", " to", " be")
      val state = freshState
      val allThinking = fragments.flatMap { frag =>
        val chunk = obj(
          "choices" -> arr(obj(
            "delta" -> obj(
              "content"   -> str(""),
              "reasoning" -> str(frag)
            ),
            "finish_reason" -> Null,
            "index"         -> num(0)
          ))
        )
        OpenAIChatCompletions.parseChunk(chunk, state, config).collect {
          case t: ProviderEvent.ThinkingDelta => t.text
        }
      }
      allThinking shouldBe fragments
      rapid.Task.pure(succeed)
    }
  }
}
