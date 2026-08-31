package sigil.provider.openai

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.{Provider, ProviderEvent, ToolCallAccumulator}
import sigil.tool.ToolRoster
import spec.{AbstractProviderConformanceSpec, TestSigil}

/**
 * [[AbstractProviderConformanceSpec]] pins for [[OpenAIProvider]]
 * (Responses wire): the [[sigil.provider.SchemaDialect.OpenAIStrict]]
 * dialect, the Responses SSE event grammar, and the `error` event
 * surface (which this provider reports as a
 * [[ProviderEvent.Error]] so the `previous_response_not_found`
 * invalidation marker can precede it).
 */
class OpenAIProviderConformanceSpec extends AbstractProviderConformanceSpec {

  private lazy val provider = OpenAIProvider(apiKey = "sk-test-placeholder", sigilRef = TestSigil)

  override protected def providerInstance: Provider = provider

  override protected def modelId: Id[Model] = Model.id("openai", "conformance-model")

  private def parseAll(lines: List[String]): Vector[ProviderEvent] = {
    val state = new provider.StreamState(
      new ToolCallAccumulator(ToolRoster(corpus), providerKey = "openai")
    )
    lines.toVector.flatMap(l => provider.parseLine(l, state))
  }

  override protected def toolTurnEvents: Vector[ProviderEvent] = parseAll(List(
    """data: {"type":"response.created","response":{"id":"resp_conf1"}}""",
    """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_conf1","call_id":"call_conf1","name":"conformance_optionals"}}""",
    """data: {"type":"response.function_call_arguments.delta","delta":"{\"title\":\"t\"}"}""",
    """data: {"type":"response.completed","response":{"id":"resp_conf1","status":"completed","usage":{"input_tokens":11,"output_tokens":7,"total_tokens":18}}}"""
  ))

  // A `message` output item narrating the plan, then the `function_call`
  // item — the shape a model takes when it speaks before acting.
  override protected def proseWithToolTurnEvents: Vector[ProviderEvent] = parseAll(List(
    """data: {"type":"response.created","response":{"id":"resp_conf2"}}""",
    """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg_conf2","role":"assistant"}}""",
    """data: {"type":"response.output_text.delta","delta":"Working on it."}""",
    """data: {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_conf2","call_id":"call_conf2","name":"conformance_optionals"}}""",
    """data: {"type":"response.function_call_arguments.delta","delta":"{\"title\":\"t\"}"}""",
    """data: {"type":"response.completed","response":{"id":"resp_conf2","status":"completed","usage":{"input_tokens":11,"output_tokens":7,"total_tokens":18}}}"""
  ))

  override protected def midStreamErrorOutcome: Either[Throwable, Vector[ProviderEvent]] =
    try Right(parseAll(List(
        """data: {"type":"error","error":{"code":"server_error","message":"upstream exploded mid-stream"}}"""
      )))
    catch { case t: Throwable => Left(t) }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
