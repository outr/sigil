package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ProviderEvent, ToolCallAccumulator}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.provider.wire.OpenAIChatCompletions.{Config, ForcedCallShape, StreamState}
import sigil.tool.core.CoreTools

/**
 * Regression for Sigil bug #179 — when the wire delivers a streamed
 * `respond` tool call (id + name in chunk 1, content args in chunks
 * 2+, finish_reason: "tool_calls", then a usage chunk), the
 * accumulator MUST emit `ContentBlockDelta` events from the streaming
 * args so the orchestrator's streaming-Message path fires (setting
 * `activeMessageId`, which gives the trailing `Usage` event a target
 * MessageDelta to attach to). Without this, every respond Message
 * settled with `usage = TokenUsage(0,0,0)` and the cost projection
 * silently zeroed out every turn.
 */
class RespondStreamLifecycleSpec extends AnyWordSpec with Matchers {

  private val cfg = Config(
    providerNamespace = "test",
    providerName = "Test",
    strictModeCapable = true,
    honorsStrict = true,
    forcedCallShape = ForcedCallShape.ToolChoice
  )

  private val tools = CoreTools.all.toVector

  private def runWire(rawChunks: List[String]): Vector[ProviderEvent] = {
    val state = new StreamState(new ToolCallAccumulator(tools, providerKey = "test"))
    val out = Vector.newBuilder[ProviderEvent]
    rawChunks.foreach(c => out ++= OpenAIChatCompletions.parseChunk(JsonParser(c), state, cfg))
    out ++= state.flushDone(cfg)
    out.result()
  }

  "Bug #179 — streamed respond through the wire" should {

    "emit ContentBlockDelta events as args stream in (so orchestrator opens activeMessageId)" in {
      val events = runWire(List(
        // chunk 1: header (id + name) with empty args
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"respond","arguments":""}}]}}]}""",
        // chunk 2: opening object + start of content key
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"topicLabel\":\"hi\",\"topicSummary\":\"greet\",\"content\":\"Hello"}}]}}]}""",
        // chunk 3: more content chars
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":", world.\""}}]}}]}""",
        // chunk 4: finish args
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":",\"disposition\":\"Success\",\"endsTurn\":true,\"keywords\":[]}"}}]}}]}""",
        // chunk 5: finish_reason
        """{"choices":[{"finish_reason":"tool_calls","delta":{}}]}""",
        // chunk 6: usage
        """{"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}"""
      ))

      val starts = events.collect { case s: ProviderEvent.ToolCallStart => s }
      val deltas = events.collect { case d: ProviderEvent.ContentBlockDelta => d }
      val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
      val usages = events.collect { case u: ProviderEvent.Usage => u }

      starts should have size 1
      starts.head.toolName shouldBe "respond"

      // The load-bearing assertion: ContentBlockDelta events fire as
      // the content field's value materializes character-by-character.
      // Pre-regression this happened; post-regression it stopped, so
      // the orchestrator's streaming Message lifecycle (Active start
      // → MessageDelta(content) → MessageDelta(Complete, usage)) never
      // ran and every respond settled with usage=(0,0,0).
      deltas should not be empty
      deltas.map(_.text).mkString should include("Hello, world.")

      completes should have size 1
      usages should have size 1
      usages.head.usage.promptTokens shouldBe 100
      usages.head.usage.completionTokens shouldBe 20
    }
  }
}
