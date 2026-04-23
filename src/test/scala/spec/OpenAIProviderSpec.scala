package spec

import lightdb.id.Id
import rapid.Task
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.openai.OpenAIProvider

/**
 * Live OpenAI coverage via [[AbstractProviderSpec]]. Skipped cleanly
 * when `OPENAI_API_KEY` is not set — a single info message, no
 * per-test cancellations.
 *
 * Uses the Responses API default endpoint (`api.openai.com`); the
 * provider itself supports custom base URLs for gateway setups.
 */
class OpenAIProviderSpec extends AbstractProviderSpec {
  override protected val provider: Task[Provider] =
    OpenAIProvider.create(TestSigil, OpenAILiveSupport.apiKey.getOrElse("")).singleton

  // Use gpt-5.4-nano by default — lowest cost, supports the full feature set
  // including built-in tools. Override via env if a specific model is required.
  override protected def modelId: Id[Model] =
    Model.id(sys.env.getOrElse("OPENAI_TEST_MODEL", "openai/gpt-5.4-nano"))

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    OpenAILiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }
}
