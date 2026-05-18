package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ProviderEvent, ProviderStreamException, ToolCallAccumulator}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.provider.wire.OpenAIChatCompletions.{Config, ForcedCallShape, StreamState}

/**
 * Regression for Sigil audit H3 — `delta.refusal` surfacing on OpenAI's
 * chat-completions wire. Pre-fix the parser ignored `delta.refusal`
 * entirely: a model that declined to comply produced an empty assistant
 * turn whose finish_reason was `stop` (not `length`), so the empty-
 * budget-burn detector didn't catch it either. The agent saw an empty
 * Message and the UI froze on a "still typing" indicator.
 *
 * Fix: buffer `delta.refusal` across chunks and throw
 * `ProviderStreamException` at stream close so the strategy can route to
 * another candidate (typed exception classifies as Fallthrough — H5).
 */
class RefusalDeltaSpec extends AnyWordSpec with Matchers {

  private val cfg: Config = Config(
    providerNamespace = "test",
    providerName = "Test",
    strictModeCapable = true,
    honorsStrict = true,
    forcedCallShape = ForcedCallShape.ToolChoice
  )

  private def refusalChunk(text: String): fabric.Json =
    JsonParser(s"""{"choices":[{"delta":{"refusal":${fabric.io.JsonFormatter.Compact(fabric.str(text))}}}]}""")

  private def contentChunk(text: String): fabric.Json =
    JsonParser(s"""{"choices":[{"delta":{"content":${fabric.io.JsonFormatter.Compact(fabric.str(text))}}}]}""")

  private def finishChunk(reason: String): fabric.Json =
    JsonParser(s"""{"choices":[{"finish_reason":"$reason","delta":{}}]}""")

  private def runUntilFlush(chunks: List[fabric.Json]): Vector[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(Vector.empty, providerKey = "test"))
    val events = Vector.newBuilder[ProviderEvent]
    chunks.foreach(ch => events ++= OpenAIChatCompletions.parseChunk(ch, state, cfg))
    events ++= state.flushDone(cfg)
    events.result()
  }

  "Audit H3 — delta.refusal" should {

    "throw ProviderStreamException with type='refusal' when the stream closes with refusal text" in {
      val ex = intercept[ProviderStreamException] {
        runUntilFlush(List(
          refusalChunk("I can't help with that."),
          finishChunk("stop")
        ))
      }
      ex.typ shouldBe "refusal"
      ex.code shouldBe 200
      ex.message_ should include("I can't help with that.")
    }

    "concatenate refusal text streamed across multiple chunks" in {
      val ex = intercept[ProviderStreamException] {
        runUntilFlush(List(
          refusalChunk("I'm sorry, "),
          refusalChunk("but I can't "),
          refusalChunk("help with that."),
          finishChunk("stop")
        ))
      }
      ex.message_ should include("I'm sorry, but I can't help with that.")
    }

    "ignore null refusal fields (no throw)" in {
      val state = new StreamState(new ToolCallAccumulator(Vector.empty, providerKey = "test"))
      val nullRefusalChunk = JsonParser("""{"choices":[{"delta":{"refusal":null}}]}""")
      OpenAIChatCompletions.parseChunk(nullRefusalChunk, state, cfg)
      OpenAIChatCompletions.parseChunk(contentChunk("hi"), state, cfg)
      OpenAIChatCompletions.parseChunk(finishChunk("stop"), state, cfg)
      noException should be thrownBy state.flushDone(cfg)
    }

    "ignore empty-string refusal fields (no throw)" in {
      val state = new StreamState(new ToolCallAccumulator(Vector.empty, providerKey = "test"))
      OpenAIChatCompletions.parseChunk(refusalChunk(""), state, cfg)
      OpenAIChatCompletions.parseChunk(contentChunk("hi"), state, cfg)
      OpenAIChatCompletions.parseChunk(finishChunk("stop"), state, cfg)
      noException should be thrownBy state.flushDone(cfg)
    }

    "still emit a Done event for non-refusal streams" in {
      val events = runUntilFlush(List(contentChunk("hello"), finishChunk("stop")))
      events.collect { case d: ProviderEvent.Done => d } should have size 1
    }
  }
}
