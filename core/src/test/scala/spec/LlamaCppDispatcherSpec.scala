package spec

import lightdb.id.Id
import org.scalatest.{Args, Status}
import rapid.Task
import sigil.db.Model
import sigil.provider.Provider
import sigil.provider.llamacpp.LlamaCppProvider

/**
 * Live-tier dispatcher coverage hitting the public llama.cpp host.
 * Gated behind `SIGIL_LIVE=1` because the multi-step chain
 * (find_capability → invoke discovered tool → respond) is sensitive to
 * qwen3.5-9b stochastic output — even at temperature 0 the model
 * intermittently settles after the discovery call without following
 * through. Recording a deterministic fixture isn't viable: across 10+
 * record attempts the chain reliably fails. The chain IS the contract
 * under test; the spec stays opt-in and runs against the live host
 * when `SIGIL_LIVE=1` is explicitly set.
 */
class LlamaCppDispatcherSpec extends AbstractDispatcherSpec {
  override protected val provider: Task[Provider] = LlamaCppProvider(TestSigil, TestSigil.llamaCppHost).singleton

  override protected def modelId: Id[Model] = Model.id("qwen3.5-9b-q4_k_m")

  override def run(testName: Option[String], args: Args): Status =
    LiveProbe.requireLiveEnabled(this).getOrElse(super.run(testName, args))

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
