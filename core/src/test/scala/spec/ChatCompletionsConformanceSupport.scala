package spec

import sigil.provider.{ProviderEvent, ToolCallAccumulator}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.tool.ToolRoster

/**
 * Streaming-pin implementation shared by every provider whose wire is
 * the OpenAI chat-completions SSE dialect. Drives canned SSE lines
 * through [[OpenAIChatCompletions.parseLine]] with the provider's OWN
 * wire config, so the conformance pins exercise the exact per-provider
 * knobs (id normalizer, inline-error policy, dialect) the live stream
 * uses.
 */
trait ChatCompletionsConformanceSupport { this: AbstractProviderConformanceSpec =>

  /** The provider's actual wire config. */
  protected def chatWireConfig: OpenAIChatCompletions.Config

  private def parseAll(lines: List[String]): Vector[ProviderEvent] = {
    val state = new OpenAIChatCompletions.StreamState(
      new ToolCallAccumulator(ToolRoster(corpus), providerKey = chatWireConfig.providerName)
    )
    lines.toVector.flatMap(l => OpenAIChatCompletions.parseLine(l, state, chatWireConfig))
  }

  override protected def toolTurnEvents: Vector[ProviderEvent] = parseAll(List(
    """data: {"choices":[{"delta":{"content":"Working on it."}}]}""",
    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_conform1","function":{"name":"conformance_optionals","arguments":"{\"title\":\"t\"}"}}]}}]}""",
    """data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}""",
    """data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}""",
    "data: [DONE]"
  ))

  override protected def midStreamErrorOutcome: Either[Throwable, Vector[ProviderEvent]] =
    try Right(parseAll(List(
      """data: {"choices":[{"delta":{"content":"partial"}}]}""",
      """data: {"error":{"code":502,"message":"upstream exploded mid-stream"}}"""
    )))
    catch { case t: Throwable => Left(t) }
}
