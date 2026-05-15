package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ProviderEvent, ProviderStreamException, ToolCallAccumulator}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.provider.wire.OpenAIChatCompletions.{Config, ForcedCallShape, StreamState}

/**
 * Coverage for a degenerate provider stream: the model burns
 * `completion_tokens` but emits no content, no reasoning, no
 * tool_calls, AND no `finish_reason`. The previous empty-budget-burn
 * detection only caught the `finish_reason: "length"` flavor; this
 * spec locks the parallel no-finish-reason path.
 *
 * Surfaces as `ProviderStreamException(typ = "empty_completion")` so
 * [[sigil.provider.ProviderStrategy]] can route around it via the
 * default [[sigil.provider.ErrorClassifier]]'s Fallthrough mapping.
 */
class EmptyCompletionDetectionSpec extends AnyWordSpec with Matchers {

  private val cfg = Config(
    providerNamespace = "test",
    providerName      = "Test",
    strictModeCapable = true,
    honorsStrict      = true,
    forcedCallShape   = ForcedCallShape.ToolChoice,
    emptyBudgetBurnThrows = true
  )

  private def runChunks(rawChunks: List[String]): Either[ProviderStreamException, Vector[ProviderEvent]] = {
    val state = new StreamState(new ToolCallAccumulator(Vector.empty, providerKey = "test"))
    val out = Vector.newBuilder[ProviderEvent]
    try {
      rawChunks.foreach { c => out ++= OpenAIChatCompletions.parseChunk(JsonParser(c), state, cfg) }
      out ++= state.flushDone(cfg)
      Right(out.result())
    } catch {
      case e: ProviderStreamException => Left(e)
    }
  }

  "Empty-completion detection" should {

    "throw ProviderStreamException(typ=empty_completion) when stream closes with no finish_reason and non-zero completion_tokens" in {
      val result = runChunks(List(
        // Empty assistant role chunk (warmup).
        """{"choices":[{"delta":{"role":"assistant","content":null}}]}""",
        // Trailing usage chunk with non-zero completion_tokens but no
        // content / reasoning / tool_calls / finish_reason ever emitted.
        """{"usage":{"prompt_tokens":1000,"completion_tokens":146,"total_tokens":1146}}"""
      ))
      result.isLeft shouldBe true
      val ex = result.swap.toOption.get
      ex.typ shouldBe "empty_completion"
      ex.message_ should include ("146")
    }

    "NOT throw when the stream emits content (healthy)" in {
      val result = runChunks(List(
        """{"choices":[{"delta":{"content":"Hello, world."}}]}""",
        """{"choices":[{"finish_reason":"stop","delta":{}}]}""",
        """{"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}"""
      ))
      result.isRight shouldBe true
    }

    "NOT throw when the stream emits a tool call (healthy)" in {
      val result = runChunks(List(
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"foo","arguments":"{}"}}]}}]}""",
        """{"choices":[{"finish_reason":"tool_calls","delta":{}}]}""",
        """{"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}"""
      ))
      result.isRight shouldBe true
    }

    "NOT throw on a truly-empty stream (no usage chunk — possibly cancelled)" in {
      val result = runChunks(List(
        """{"choices":[{"delta":{"role":"assistant","content":null}}]}"""
      ))
      result.isRight shouldBe true
      result.toOption.get shouldBe empty
    }

    "NOT throw when emptyBudgetBurnThrows is disabled" in {
      val state = new StreamState(new ToolCallAccumulator(Vector.empty, providerKey = "test"))
      val cfgOff = cfg.copy(emptyBudgetBurnThrows = false)
      val out = Vector.newBuilder[ProviderEvent]
      out ++= OpenAIChatCompletions.parseChunk(
        JsonParser("""{"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}"""),
        state, cfgOff
      )
      noException should be thrownBy state.flushDone(cfgOff)
    }
  }
}
