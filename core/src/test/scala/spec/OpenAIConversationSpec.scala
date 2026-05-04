package spec

import lightdb.id.Id
import rapid.Task
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.openai.OpenAIProvider

/**
 * End-to-end conversation coverage against the live OpenAI Responses
 * API via the full HTTP server + DurableSocket wire. Skipped cleanly
 * when `OPENAI_API_KEY` is not set.
 */
class OpenAIConversationSpec extends AbstractConversationSpec {
  override protected val provider: Task[Provider] =
    OpenAIProvider.create(TestSigil, OpenAILiveSupport.apiKey.getOrElse("")).singleton

  // gpt-5.4-mini (not -nano): the conversation spec exercises agentic
  // behavior — `change_mode` firing from a non-leading coding prompt —
  // which nano skips. The provider-level specs stay on nano since they
  // don't depend on multi-tool agentic flow.
  override protected def modelId: Id[Model] =
    Model.id(sys.env.getOrElse("OPENAI_TEST_MODEL", "openai/gpt-5.4-mini"))

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    OpenAILiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
