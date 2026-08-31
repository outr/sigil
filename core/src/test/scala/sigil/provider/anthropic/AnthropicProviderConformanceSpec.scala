package sigil.provider.anthropic

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.{Provider, ProviderEvent, ToolCallAccumulator}
import sigil.tool.ToolRoster
import spec.{AbstractProviderConformanceSpec, TestSigil}

/**
 * [[AbstractProviderConformanceSpec]] pins for [[AnthropicProvider]]:
 * the [[sigil.provider.SchemaDialect.Anthropic]] dialect, the Messages
 * SSE event grammar, and the `event: error` mid-stream surface.
 */
class AnthropicProviderConformanceSpec extends AbstractProviderConformanceSpec {

  private lazy val provider = AnthropicProvider(apiKey = "sk-ant-test-placeholder", sigilRef = TestSigil)

  override protected def providerInstance: Provider = provider

  override protected def modelId: Id[Model] = Model.id("anthropic", "conformance-model")

  private def parseAll(lines: List[String]): Vector[ProviderEvent] = {
    val state = new provider.StreamState(
      new ToolCallAccumulator(ToolRoster(corpus), providerKey = "anthropic")
    )
    lines.toVector.flatMap(l => provider.parseLine(l, state))
  }

  override protected def toolTurnEvents: Vector[ProviderEvent] = parseAll(List(
    """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_conf1","name":"conformance_optionals"}}""",
    """data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"title\":\"t\"}"}}""",
    """data: {"type":"content_block_stop","index":0}""",
    """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":11,"output_tokens":7}}""",
    """data: {"type":"message_stop"}"""
  ))

  // A `text` content block preceding the `tool_use` block — the shape a
  // model takes when it narrates its plan before acting.
  override protected def proseWithToolTurnEvents: Vector[ProviderEvent] = parseAll(List(
    """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Working on it."}}""",
    """data: {"type":"content_block_stop","index":0}""",
    """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_conf2","name":"conformance_optionals"}}""",
    """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"title\":\"t\"}"}}""",
    """data: {"type":"content_block_stop","index":1}""",
    """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":11,"output_tokens":7}}""",
    """data: {"type":"message_stop"}"""
  ))

  override protected def midStreamErrorOutcome: Either[Throwable, Vector[ProviderEvent]] =
    try Right(parseAll(List(
        """data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
      )))
    catch { case t: Throwable => Left(t) }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
