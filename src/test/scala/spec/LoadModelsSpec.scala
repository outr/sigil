package spec

import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.Sigil
import sigil.controller.OpenRouter
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.tool.ToolInput

class LoadModelsSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  "Load models" should {
    "properly load the models from OpenRouter" in {
      OpenRouter.loadModels.map { models =>
        models.length should be > 0
      }
    }
    "refresh models in the database" in {
      OpenRouter.refreshModels(Test).next {
        Test.instance.flatMap { sigil =>
          sigil.db.model.transaction(_.count).map { count =>
            count should be > 0
          }
        }
      }
    }
    "find all the OpenAI models" in {
      Test.cache.findModel(provider = Some("openai")).toList.map { list =>
        list.find(_.model == "gpt-5.4") should not be None
      }
    }
    "load model by provider + model" in {
      Test.cache(provider = "openai", model = "gpt-5.4").map { model =>
        model should not be None
      }
    }
  }

  object Test extends Sigil {
    override protected def events: List[RW[? <: Event]] = Nil
    override protected def toolInputs: List[RW[? <: ToolInput]] = Nil
    override protected def participantIds: List[RW[? <: ParticipantId]] = Nil
  }
}
