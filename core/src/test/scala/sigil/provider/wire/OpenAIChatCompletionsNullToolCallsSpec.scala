package sigil.provider.wire

import fabric.{Null, arr, num, obj, str}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.provider.ToolCallAccumulator

/**
 * Coverage for sigil bug #163 — DeepInfra streams `tool_calls: null`
 * on chat-completion deltas that carry no tool-call data (the
 * `role: "assistant"` warmup chunk emits it before any content).
 * Pre-fix, `parseChunk` called `tcs.asVector` on the JSON null and
 * raised `null is a Null, not a Arr`, blowing up the agent loop.
 * Post-fix, the null tool_calls field is skipped exactly like the
 * existing `content` and `reasoning_content` null guards.
 */
class OpenAIChatCompletionsNullToolCallsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  spec.TestSigil.initFor(getClass.getSimpleName)

  private val config = OpenAIChatCompletions.Config(
    providerNamespace = "test",
    providerName = "Test"
  )

  private def freshState = new OpenAIChatCompletions.StreamState(new ToolCallAccumulator(Vector.empty))

  "OpenAIChatCompletions.parseChunk (Bug #163)" should {

    "tolerate tool_calls: null in a delta chunk (DeepInfra wire shape)" in {
      val chunk = obj(
        "choices" -> arr(
          obj(
            "delta" -> obj(
              "role" -> str("assistant"),
              "content" -> str(""),
              "reasoning_content" -> Null,
              "tool_calls" -> Null
            ),
            "finish_reason" -> Null,
            "index" -> num(0)
          )
        )
      )
      // Pre-fix this threw fabric's "null is a Null, not a Arr".
      noException should be thrownBy OpenAIChatCompletions.parseChunk(chunk, freshState, config)
      rapid.Task.pure(succeed)
    }

    "still parse a real tool_calls array when present" in {
      val chunk = obj(
        "choices" -> arr(
          obj(
            "delta" -> obj(
              "tool_calls" -> arr(
                obj(
                  "index" -> num(0),
                  "id" -> str("call_abc"),
                  "type" -> str("function"),
                  "function" -> obj("name" -> str("respond"), "arguments" -> str("{}"))
                )
              )
            ),
            "index" -> num(0)
          )
        )
      )
      val state = freshState
      OpenAIChatCompletions.parseChunk(chunk, state, config)
      state.hasUsefulOutput shouldBe true
    }

    "still pass empty tool_calls array through (no items, no throw)" in {
      val chunk = obj(
        "choices" -> arr(
          obj(
            "delta" -> obj("tool_calls" -> arr()),
            "index" -> num(0)
          )
        )
      )
      val state = freshState
      noException should be thrownBy OpenAIChatCompletions.parseChunk(chunk, state, config)
      state.hasUsefulOutput shouldBe false
      rapid.Task.pure(succeed)
    }
  }

  "tear down" should {
    "dispose TestSigil" in spec.TestSigil.shutdown.map(_ => succeed)
  }
}
