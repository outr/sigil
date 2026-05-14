package spec

import lightdb.id.Id
import rapid.Task
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.openrouter.OpenRouterProvider

/**
 * Live OpenRouter coverage via [[AbstractProviderSpec]]. Self-skips
 * cleanly when `SIGIL_LIVE != 1` or `OPEN_ROUTER_TOKEN` is unset.
 * Probe-gates on credentials — a 401/402/403 from the minimal
 * chat-completions probe cancels the suite rather than failing
 * every test.
 *
 * The default model is `openai/gpt-4o-mini` (cheap, broadly
 * available, well-behaved on the multi-mode + respond-roundtrip
 * coverage in [[AbstractProviderSpec]]). Override via
 * `OPENROUTER_TEST_MODEL` for vendor-specific verification.
 */
class OpenRouterLiveSpec extends AbstractProviderSpec {
  override protected val provider: Task[Provider] =
    OpenRouterProvider
      .create(TestSigil, OpenRouterLiveSupport.apiKey.getOrElse(""))
      .map(p => p: Provider)
      .singleton

  override protected def modelId: Id[Model] =
    Model.id(sys.env.getOrElse("OPENROUTER_TEST_MODEL", "openai/gpt-4o-mini"))

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    OpenRouterLiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
