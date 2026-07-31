package sigil.provider.google

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.{Provider, ProviderEvent, ToolCallAccumulator}
import sigil.tool.ToolRoster
import spec.{AbstractProviderConformanceSpec, TestSigil}

/**
 * [[AbstractProviderConformanceSpec]] pins for [[GoogleProvider]]: the
 * [[sigil.provider.SchemaDialect.Gemini]] dialect, the
 * `streamGenerateContent` SSE grammar, and the embedded-`error`
 * mid-stream surface.
 */
class GoogleProviderConformanceSpec extends AbstractProviderConformanceSpec {

  private lazy val provider = GoogleProvider(apiKey = "test-placeholder", sigilRef = TestSigil)

  override protected def providerInstance: Provider = provider

  override protected def modelId: Id[Model] = Model.id("google", "conformance-model")

  private def parseAll(lines: List[String]): Vector[ProviderEvent] = {
    val state = new provider.StreamState(
      new ToolCallAccumulator(ToolRoster(corpus), providerKey = "google")
    )
    lines.toVector.flatMap(l => provider.parseLine(l, state))
  }

  override protected def toolTurnEvents: Vector[ProviderEvent] = parseAll(List(
    """data: {"candidates":[{"content":{"parts":[{"text":"Working on it."}]}}]}""",
    """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"conformance_optionals","args":{"title":"t"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":7,"totalTokenCount":18}}"""
  ))

  override protected def midStreamErrorOutcome: Either[Throwable, Vector[ProviderEvent]] =
    try Right(parseAll(List(
      """data: {"error":{"code":429,"message":"quota exhausted","status":"RESOURCE_EXHAUSTED"}}"""
    )))
    catch { case t: Throwable => Left(t) }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
