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
    "refresh models in the database" in
      OpenRouter.refreshModels(TestSigil).next {
        TestSigil.instance.flatMap { sigil =>
          sigil.db.model.transaction(_.count).map { count =>
            count should be > 0
          }
        }
      }
    "find all the OpenAI models" in
      TestSigil.cache.findModel(provider = Some("openai")).toList.map { list =>
        list.find(_.model == "gpt-5.4") should not be None
      }
    "load model by provider + model" in
      TestSigil.cache(provider = "openai", model = "gpt-5.4").map { model =>
        model should not be None
      }
  }
}
