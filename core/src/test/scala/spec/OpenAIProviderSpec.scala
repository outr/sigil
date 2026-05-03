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

  // gpt-5.4-mini (not -nano): the inherited `should switch modes` test in
  // [[AbstractProviderSpec]] exercises `change_mode` firing from a non-
  // leading coding prompt ("I need to write a Scala function"). gpt-5.4-nano
  // runs at locked temperature 1.0 with `effort: "none"` by default — at
  // those settings it picks `respond` (asking clarifying questions) over
  // `change_mode` for the test prompt, which the same test passes on
  // Anthropic / Google / DeepSeek (those honour temperature 0.0). gpt-5.4-mini
  // has the same locked sampling but reliably fires `change_mode` because
  // it actually exercises mode-switch decisions. Matches the rationale in
  // [[OpenAIConversationSpec]] which made the same model bump for the
  // same reason. Override via env if a specific model is required.
  override protected def modelId: Id[Model] =
    Model.id(sys.env.getOrElse("OPENAI_TEST_MODEL", "openai/gpt-5.4-mini"))

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    OpenAILiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }
}
