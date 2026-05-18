package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ProviderEvent, ToolCallAccumulator}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.provider.wire.OpenAIChatCompletions.{Config, StreamState}

/**
 * Sigil bug #228 — OpenAI-compatible proxies (observed: OpenRouter
 * forwarding Kimi K2.5/K2.6 via Chutes) can split the end-of-stream
 * notification across TWO SSE chunks:
 *
 *   chunk 1: `finish_reason: tool_calls` (no usage)
 *   chunk 2: `finish_reason: tool_calls` (re-announced) + `usage` block
 *   [DONE]
 *
 * Neither chunk carries new tool-call argument data — chunk 2's
 * purpose is to attach the `usage` followup. Sigil's wire decoder
 * used to call `state.acc.complete()` on every `finish_reason` chunk
 * regardless of whether the run had already settled, so the
 * orchestrator received the same `ToolCallComplete` twice and the
 * orchestrator's defensive dedupe fired a noisy warning every time.
 *
 * The fix guards the completion-emitting branch on
 * `state.pendingDone.isEmpty`. The second chunk's usage block still
 * flows through (the `usage` handler runs after the `finish_reason`
 * branch and is unaffected by the guard); the duplicate completion
 * is suppressed at the wire layer where the context is rich enough
 * to recognise the usage-followup shape.
 */
class StreamingFinishReasonSplitSpec extends AnyWordSpec with Matchers {

  private val cfg: Config = Config(
    providerNamespace = "openrouter",
    providerName      = "OpenRouter"
  )

  private def freshState(): StreamState =
    new StreamState(new ToolCallAccumulator(Vector.empty))

  /** Single SSE chunk announcing a tool_calls header. The accumulator
    * picks up the call id + name so a later `acc.complete()` has
    * something to emit. */
  private def toolCallStartChunk(callId: String, fnName: String): fabric.Json =
    JsonParser(
      s"""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"$callId","function":{"name":"$fnName"}}]}}]}"""
    )

  /** SSE chunk feeding argument-text fragments into the tool call. */
  private def argsDeltaChunk(callId: String, argsFragment: String): fabric.Json =
    JsonParser(
      s"""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"$callId","function":{"arguments":${fabric.io.JsonFormatter.Compact(fabric.str(argsFragment))}}}]}}]}"""
    )

  /** Bare `finish_reason: tool_calls` chunk — no usage. The first half
    * of the OpenRouter+Kimi split-finish shape. */
  private def finishOnlyChunk: fabric.Json =
    JsonParser("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""")

  /** `finish_reason: tool_calls` chunk WITH the `usage` block — the
    * second half of the split-finish shape. */
  private def finishWithUsageChunk(prompt: Int, completion: Int): fabric.Json =
    JsonParser(
      s"""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":${prompt + completion}}}"""
    )

  /** Combined chunk — single `finish_reason` carrying both completion
    * and usage. The conventional OpenAI shape; regression guard for
    * the common path. */
  private def finishAndUsageChunk(prompt: Int, completion: Int): fabric.Json =
    JsonParser(
      s"""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":${prompt + completion}}}"""
    )

  /** Anything `state.acc.complete()` emits — ToolCallComplete when the
    * accumulator has a tool definition for the call's name, Error
    * otherwise. The bug is about the accumulator's `complete()` being
    * invoked twice; whichever shape it returns, the duplicate is the
    * defect we're catching here. */
  private def completions(events: Vector[ProviderEvent]): Vector[ProviderEvent] =
    events.collect {
      case c: ProviderEvent.ToolCallComplete => c
      case e: ProviderEvent.Error            => e
    }

  private def usages(events: Vector[ProviderEvent]): Vector[ProviderEvent.Usage] =
    events.collect { case u: ProviderEvent.Usage => u }

  "OpenAIChatCompletions split-finish handling (sigil bug #228)" should {

    "emit exactly one ToolCallComplete when finish_reason arrives in two consecutive chunks (OpenRouter+Chutes+Kimi shape)" in {
      val state = freshState()
      val events = Vector.newBuilder[ProviderEvent]

      events ++= OpenAIChatCompletions.parseChunk(toolCallStartChunk("call_abc", "find_capability"), state, cfg)
      events ++= OpenAIChatCompletions.parseChunk(argsDeltaChunk("call_abc", """{"keywords":"foo"}"""), state, cfg)
      // Provider splits the end notification across two chunks.
      events ++= OpenAIChatCompletions.parseChunk(finishOnlyChunk, state, cfg)
      events ++= OpenAIChatCompletions.parseChunk(finishWithUsageChunk(23566, 67), state, cfg)

      val result = events.result()
      val completed = completions(result)
      val usage = usages(result)

      withClue(s"all events: $result") {
        // Exactly one completion-class event per call, even though
        // `finish_reason` arrived in two chunks. Pre-fix: two.
        completed should have size 1
        // The authoritative usage from chunk 2 still flows through —
        // the wire decoder consumes the usage block after the
        // finish_reason guard.
        usage.exists { u =>
          !u.usage.isEstimated &&
            u.usage.promptTokens == 23566 &&
            u.usage.completionTokens == 67
        } shouldBe true
      }
    }

    "still emit exactly one completion on the conventional single-finish shape (regression guard)" in {
      val state = freshState()
      val events = Vector.newBuilder[ProviderEvent]

      events ++= OpenAIChatCompletions.parseChunk(toolCallStartChunk("call_xyz", "find_capability"), state, cfg)
      events ++= OpenAIChatCompletions.parseChunk(argsDeltaChunk("call_xyz", """{"keywords":"foo"}"""), state, cfg)
      events ++= OpenAIChatCompletions.parseChunk(finishAndUsageChunk(100, 50), state, cfg)

      val result = events.result()
      val completed = completions(result)
      val usage = usages(result)

      withClue(s"all events: $result") {
        completed should have size 1
        usage.exists { u =>
          !u.usage.isEstimated && u.usage.promptTokens == 100 && u.usage.completionTokens == 50
        } shouldBe true
      }
    }
  }
}
