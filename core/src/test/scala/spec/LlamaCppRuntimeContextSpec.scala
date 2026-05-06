package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.llamacpp.LlamaCpp

/**
 * Coverage for sigil bug #42 — `LlamaCpp.toModel` must prefer the
 * runtime `n_ctx` (read from `/props`) over the training-time
 * `n_ctx_train` (read from `/v1/models.meta`). The runtime value is
 * the hard limit; using the training value produces budget decisions
 * that are fictional whenever the operator allocated less than the
 * model's full training window.
 */
class LlamaCppRuntimeContextSpec extends AnyWordSpec with Matchers {

  private val gemma = LlamaCpp.Entry(
    id = "/models/gemma-4-26b.gguf",
    created = Some(0L),
    ownedBy = Some("local"),
    meta = Some(LlamaCpp.Meta(
      nParams = Some(26_000_000_000L),
      nCtxTrain = Some(262_144L),
      nEmbd = Some(8192),
      nVocab = Some(256_000),
      size = Some(50_000_000_000L)
    ))
  )

  "LlamaCpp.toModel" should {
    "prefer the runtime contextLength override over the training meta value" in {
      val model = LlamaCpp.toModel(gemma, runtimeContextOverride = Some(32_768L))
      model.contextLength shouldBe 32_768L
    }

    "fall back to meta.nCtxTrain when no runtime override is supplied" in {
      val model = LlamaCpp.toModel(gemma, runtimeContextOverride = None)
      model.contextLength shouldBe 262_144L
    }

    "use 0L when neither runtime override nor training meta is available" in {
      val sparse = gemma.copy(meta = Some(LlamaCpp.Meta()))
      val model = LlamaCpp.toModel(sparse, runtimeContextOverride = None)
      model.contextLength shouldBe 0L
    }

    "use the runtime override even when it's smaller than the training value" in {
      // The whole point of the bug fix — runtime is the hard limit.
      val model = LlamaCpp.toModel(gemma, runtimeContextOverride = Some(8192L))
      model.contextLength shouldBe 8192L
    }

    "propagate the contextLength to topProvider.contextLength too" in {
      val model = LlamaCpp.toModel(gemma, runtimeContextOverride = Some(32_768L))
      model.topProvider.contextLength shouldBe Some(32_768L)
    }

    "default `runtimeContextOverride = None` (preserves callers that don't pass the new arg)" in {
      val model = LlamaCpp.toModel(gemma)
      model.contextLength shouldBe 262_144L
    }
  }
}
