package sigil.provider.anthropic

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{ProviderEvent, ToolCallAccumulator}
import sigil.tool.core.NoResponseTool
import sigil.tool.model.NoResponseInput
import spec.TestSigil
import spice.net.url
import sigil.tool.ToolRoster

/**
 * Canned-SSE decode coverage for [[AnthropicProvider]]'s streaming
 * `tool_use` accumulation. Feeds hand-written Anthropic SSE event
 * lines straight through the parser — no live round-trip, no API key
 * — so it runs in CI on every build, unlike the live `Anthropic*Spec`
 * suites that self-skip without credentials.
 *
 * Regression for Sigil #260: a zero-parameter tool call arrives as a
 * `tool_use` block with NO `input_json_delta` events (the model has
 * no argument JSON to emit). The accumulator left the argument buffer
 * empty, `JsonParser("")` yielded `Json.Null`, and decoding `null`
 * into the typed input threw "Unsupported token: null" — the tool
 * never ran. An empty buffer must decode as the empty object `{}`.
 */
class AnthropicToolCallDecodeSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = AnthropicProvider("test-key", TestSigil, url"https://api.anthropic.com")

  /**
   * Feed canned SSE `data:` lines through the streaming decoder with
   * a fresh state, collecting every emitted ProviderEvent.
   */
  private def decode(lines: List[String]): Vector[ProviderEvent] = {
    val state = new provider.StreamState(
      new ToolCallAccumulator(ToolRoster(Vector(NoResponseTool)), providerKey = "anthropic")
    )
    lines.toVector.flatMap(l => provider.parseLine(l, state))
  }

  private val toolUseStart =
    """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_x","name":"no_response"}}"""
  private val blockStop = """data: {"type":"content_block_stop","index":0}"""
  private val deltaStop = """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""
  private val messageStop = """data: {"type":"message_stop"}"""

  "AnthropicProvider streaming tool_use decode" should {

    "decode a zero-parameter tool_use with no input_json_delta as empty-object args (#260)" in {
      // A zero-parameter tool: Anthropic streams the tool_use block but
      // no input_json_delta at all.
      val events = decode(List(toolUseStart, blockStop, deltaStop, messageStop))

      val errors = events.collect { case e: ProviderEvent.Error => e }
      withClue(s"decode emitted errors: ${errors.mkString("; ")}: ") {
        errors shouldBe empty
      }
      // The call decoded to the typed input — no args means the
      // zero-/all-default input, never a parse failure.
      val inputs = events.flatMap { case ProviderEvent.ToolCallComplete(_, wc) => wc.inputFor(NoResponseTool); case _ => None }
      inputs shouldBe Vector(NoResponseInput())
    }

    "decode a tool_use whose args stream as input_json_delta fragments" in {
      // Normal path — args arrive split across two fragments. Guards
      // against the empty-buffer special-case breaking real args.
      val events = decode(List(
        toolUseStart,
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"reason\":"}}""",
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"all done\"}"}}""",
        blockStop,
        deltaStop,
        messageStop
      ))

      events.collect { case e: ProviderEvent.Error => e } shouldBe empty
      val inputs = events.flatMap { case ProviderEvent.ToolCallComplete(_, wc) => wc.inputFor(NoResponseTool); case _ => None }
      inputs shouldBe Vector(NoResponseInput(Some("all done")))
    }

    "emit Usage AFTER the tool-call flush and BEFORE Done — never before the completes" in {
      // The wire puts usage on `message_delta`, but the accumulator
      // flushes at `message_stop`. Emitting Usage where the wire put it
      // reached the orchestrator before any ToolCallComplete: for a
      // pure-tool-call iteration there was no settled invoke to fold
      // the usage onto and it was silently dropped — every tool-loop
      // iteration on this provider billed as zero and the
      // conversation's cost surface starved. The provider must
      // normalize to deltas → completes → Usage → Done, the ordering
      // every other backend produces.
      val events = decode(List(
        toolUseStart,
        blockStop,
        """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":10683,"output_tokens":31}}""",
        messageStop
      ))
      val kinds = events.map {
        case _: ProviderEvent.ToolCallComplete => "complete"
        case _: ProviderEvent.Usage => "usage"
        case _: ProviderEvent.Done => "done"
        case _ => "other"
      }
      val completeIdx = kinds.indexOf("complete")
      val usageIdx = kinds.indexOf("usage")
      val doneIdx = kinds.indexOf("done")
      withClue(s"event order: ${kinds.mkString(" -> ")}: ") {
        completeIdx should be >= 0
        usageIdx should be > completeIdx
        doneIdx should be > usageIdx
      }
      val usage = events.collectFirst { case ProviderEvent.Usage(u) => u }.get
      usage.promptTokens shouldBe 10683
      usage.completionTokens shouldBe 31
    }

    "decode a tool_use whose args stream as an explicit empty object" in {
      // The shape OpenAI sends for a no-args call (`"{}"`); must decode
      // identically to the no-input_json_delta case above.
      val events = decode(List(
        toolUseStart,
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
        blockStop,
        deltaStop,
        messageStop
      ))

      events.collect { case e: ProviderEvent.Error => e } shouldBe empty
      val inputs = events.flatMap { case ProviderEvent.ToolCallComplete(_, wc) => wc.inputFor(NoResponseTool); case _ => None }
      inputs shouldBe Vector(NoResponseInput())
    }
  }
}
