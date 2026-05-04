package spec

import lightdb.id.Id
import rapid.Task
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.llamacpp.LlamaCppProvider

/**
 * End-to-end conversation coverage against the local llama.cpp server
 * via the full HTTP server + DurableSocket wire. Local-only, free —
 * skips cleanly if `TestSigil.llamaCppHost` isn't reachable.
 */
class LlamaCppConversationSpec extends AbstractConversationSpec {
  override protected val provider: Task[Provider] =
    LlamaCppProvider(TestSigil, TestSigil.llamaCppHost).singleton

  override protected def modelId: Id[Model] = Model.id("qwen3.5-9b-q4_k_m")

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
