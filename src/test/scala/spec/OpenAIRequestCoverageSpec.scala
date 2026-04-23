package spec

import lightdb.id.Id
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.openai.OpenAIProvider

/**
 * OpenAI Responses wire-body regression, layered on the shared
 * [[AbstractRequestCoverageSpec]]. Marker substrings are
 * provider-agnostic; this spec adds no OpenAI-specific wire
 * assertions (the Responses API uses a different field layout than
 * chat-completions, so specific-key tests belong here rather than in
 * the abstract base).
 *
 * Deterministic — does not actually hit the OpenAI API. Uses a
 * placeholder key since `requestConverter` only builds the HTTP
 * request without sending it.
 */
class OpenAIRequestCoverageSpec extends AbstractRequestCoverageSpec {
  override protected def providerInstance: Provider =
    OpenAIProvider(apiKey = "sk-test-placeholder", models = Nil, sigilRef = TestSigil)
  override protected def modelId: Id[Model] = Model.id("openai", "gpt-5.4-nano")
}
