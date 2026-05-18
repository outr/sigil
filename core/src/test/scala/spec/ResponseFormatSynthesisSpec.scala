package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ProviderEvent, ToolCallAccumulator, ToolChoice}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.provider.wire.OpenAIChatCompletions.{Config, ForcedCallShape, ResponseFormatMode, StreamState}
import sigil.tool.ToolName
import sigil.tool.core.{FindCapabilityTool, RespondTool}

/**
 * Regression for sigil bug #173 part B — stream-side synthesis of
 * `ToolCallStart` + `ToolCallComplete` events from the buffered
 * content when the wire-side substituted `tool_choice` with
 * `response_format: json_schema`.
 *
 * In response_format mode, the model emits its tool-call payload as
 * its assistant `content` (not as `tool_calls`). The stream handler:
 *   - Suppresses TextDelta emission (no streaming Message UI for
 *     what's actually a tool call).
 *   - Buffers the content.
 *   - At `finish_reason: stop`, parses the buffer + emits synthetic
 *     `ToolCallStart` + `appendArgs` + `complete()`.
 *
 * Two modes:
 *   - `Specific(name)`: buffer IS the tool's typed input directly.
 *   - `Required`: buffer is `{tool_name, arguments}`; the helper
 *     extracts both and emits the right tool's events.
 */
class ResponseFormatSynthesisSpec extends AnyWordSpec with Matchers {

  private val cfg: Config = Config(
    providerNamespace = "test",
    providerName = "Test",
    strictModeCapable = true,
    honorsStrict = false,
    forcedCallShape = ForcedCallShape.ResponseFormatJsonSchema
  )

  /**
   * Stub an SSE chunk: content delta
   */
  private def contentChunk(text: String): fabric.Json =
    JsonParser(s"""{"choices":[{"delta":{"content":${fabric.io.JsonFormatter.Compact(fabric.str(text))}}}]}""")

  private def finishChunk(reason: String = "stop"): fabric.Json =
    JsonParser(s"""{"choices":[{"finish_reason":"$reason","delta":{}}]}""")

  private def doneChunk(): fabric.Json =
    JsonParser(s"""{"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""")

  private def runStream(mode: ResponseFormatMode, contentParts: List[String], tools: Vector[sigil.tool.Tool]): Vector[ProviderEvent] = {
    val acc = new ToolCallAccumulator(tools, providerKey = "test")
    val state = new StreamState(acc, Some(mode))
    val events = Vector.newBuilder[ProviderEvent]
    contentParts.foreach(p => events ++= OpenAIChatCompletions.parseChunk(contentChunk(p), state, cfg))
    events ++= OpenAIChatCompletions.parseChunk(finishChunk("stop"), state, cfg)
    events ++= OpenAIChatCompletions.parseChunk(doneChunk(), state, cfg)
    events ++= state.flushDone(cfg)
    events.result()
  }

  "ResponseFormatMode.Specific" should {

    val mode = ResponseFormatMode.Specific(RespondTool.schema.name)
    val tools = Vector(RespondTool: sigil.tool.Tool)
    // A valid RespondInput JSON.
    val args = """{"topicLabel":"hi","topicSummary":"greet","content":"Hello","disposition":"Success","endsTurn":true,"keywords":[]}"""

    "suppress TextDelta emission while content streams" in {
      val events = runStream(mode, List(args.substring(0, 20), args.substring(20)), tools)
      events.collect { case _: ProviderEvent.TextDelta => true } shouldBe empty
    }

    "synthesize a ToolCallStart for the named tool on finish=stop" in {
      val events = runStream(mode, List(args), tools)
      val starts = events.collect { case s: ProviderEvent.ToolCallStart => s }
      starts should have size 1
      starts.head.toolName shouldBe "respond"
    }

    "synthesize a ToolCallComplete with parsed RespondInput" in {
      val events = runStream(mode, List(args), tools)
      val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
      completes should have size 1
      completes.head.input shouldBe a[sigil.tool.model.RespondInput]
      val ri = completes.head.input.asInstanceOf[sigil.tool.model.RespondInput]
      ri.topicLabel shouldBe "hi"
      ri.content shouldBe "Hello"
    }
  }

  "ResponseFormatMode.Required" should {

    val mode = ResponseFormatMode.Required
    val tools = Vector(RespondTool: sigil.tool.Tool, FindCapabilityTool: sigil.tool.Tool)

    "look up tool_name and emit ToolCallStart for that tool" in {
      val meta = """{"tool_name":"find_capability","arguments":{"keywords":"sleep wait delay"}}"""
      val events = runStream(mode, List(meta), tools)
      val starts = events.collect { case s: ProviderEvent.ToolCallStart => s }
      starts.headOption.map(_.toolName) shouldBe Some("find_capability")
    }

    "pass the arguments object as the synthetic tool-call args" in {
      val meta = """{"tool_name":"find_capability","arguments":{"keywords":"sleep wait delay"}}"""
      val events = runStream(mode, List(meta), tools)
      val completes = events.collect { case c: ProviderEvent.ToolCallComplete => c }
      completes should have size 1
      completes.head.input shouldBe a[sigil.tool.core.FindCapabilityInput]
      completes.head.input.asInstanceOf[sigil.tool.core.FindCapabilityInput].keywords shouldBe "sleep wait delay"
    }

    "throw ProviderStreamException when content lacks tool_name" in {
      val malformed = """{"arguments":{"keywords":"x"}}""" // missing tool_name
      val acc = new ToolCallAccumulator(tools, providerKey = "test")
      val state = new StreamState(acc, Some(mode))
      OpenAIChatCompletions.parseChunk(contentChunk(malformed), state, cfg)
      val ex = intercept[sigil.provider.ProviderStreamException] {
        OpenAIChatCompletions.parseChunk(finishChunk("stop"), state, cfg)
      }
      ex.typ shouldBe "malformed_response_format"
    }
  }
}
