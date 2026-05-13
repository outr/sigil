package spec

import lightdb.id.Id
import rapid.Task
import sigil.db.Model
import sigil.provider.{GenerationSettings, Provider, ReasoningMode}
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

  // Qwen3's template defaults thinking ON; under a 4000-token budget the
  // chain-of-thought eats the response and tool calls truncate. Off makes
  // these deterministic conversation tests reliable.
  override protected def generationSettings: GenerationSettings =
    super.generationSettings.copy(reasoningMode = ReasoningMode.Off)

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
