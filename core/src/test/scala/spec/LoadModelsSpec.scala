package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.controller.OpenRouter

class LoadModelsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  // Per-suite DB path so each forked JVM gets its own RocksDB instance.
  TestSigil.initFor(getClass.getSimpleName)

  "Load models" should {
    "properly load the models from OpenRouter" in
      OpenRouter.loadModels.map { models =>
        models.length should be > 0
      }
    "refresh models in the in-memory registry" in {
      for {
        _ <- OpenRouter.refreshModels(TestSigil)
      } yield {
        TestSigil.cache.all.size should be > 0
        succeed
      }
    }
    "find all the OpenAI models" in {
      for {
        _ <- OpenRouter.refreshModels(TestSigil)
      } yield {
        TestSigil.cache.find(provider = Some("openai")).find(_.model == "gpt-5.4") should not be None
        succeed
      }
    }
    "load model by provider + model" in {
      for {
        _ <- OpenRouter.refreshModels(TestSigil)
      } yield {
        TestSigil.cache.find("openai", "gpt-5.4") should not be None
        succeed
      }
    }
  }
}
