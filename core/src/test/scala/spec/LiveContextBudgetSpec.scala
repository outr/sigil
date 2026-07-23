package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.Model
import sigil.provider.llamacpp.{LlamaCpp, LlamaCppProvider}
import sigil.provider.{Provider, ProviderCall, ProviderEvent, ProviderType}
import spice.net.url

/**
 * `Provider.liveContextBudget` — the per-request context budget seam.
 * The default answers from the model registry; providers with an
 * operator-tunable backend (llama.cpp) override with a live query so a
 * resized server moves the budget without reconstruction.
 */
class LiveContextBudgetSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private class RegistryStubProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new UnsupportedOperationException("RegistryStubProvider"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.emits(Nil)
  }

  private class LiveStubProvider(budget: Long) extends RegistryStubProvider {
    override def liveContextBudget(modelId: Id[Model]): Task[Option[Long]] = Task.pure(Some(budget))
  }

  "Provider.liveContextBudget" should {

    "answer the registry contextLength for a registered model" in {
      val modelId = Model.id("test", "live-budget-model")
      val model = TestSigil.testModel(modelId)
      new RegistryStubProvider().liveContextBudget(modelId).map { budget =>
        budget shouldBe Some(model.contextLength)
      }
    }

    "answer None for an unregistered model" in {
      new RegistryStubProvider().liveContextBudget(Model.id("test", "never-registered-budget-xyzzy")).map { budget =>
        budget shouldBe None
      }
    }

    "prefer a subclass's live value over the registry" in {
      val modelId = Model.id("test", "live-budget-model")
      TestSigil.testModel(modelId)
      new LiveStubProvider(123456L).liveContextBudget(modelId).map { budget =>
        budget shouldBe Some(123456L)
      }
    }
  }

  "LlamaCppProvider.liveContextBudget" should {

    "fall back to the registry when the server is unreachable" in {
      val modelId = Model.id("llamacpp", "budget-fallback-model")
      val model = TestSigil.testModel(modelId)
      val provider = LlamaCppProvider(url"http://127.0.0.1:1", Nil, TestSigil)
      provider.liveContextBudget(modelId).map { budget =>
        budget shouldBe Some(model.contextLength)
      }
    }

    "report the live per-slot budget from a reachable server (self-skips when unreachable)" in {
      LlamaCpp.fetchProps(TestSigil.llamaCppHost).flatMap {
        case None =>
          Task {
            scribe.info("LiveContextBudgetSpec: llama.cpp server unreachable — skipping live budget assertion")
            succeed
          }
        case Some(props) =>
          val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
          provider.liveContextBudget(Model.id("llamacpp", "any-model")).map { budget =>
            budget shouldBe Some(props.perSlotContext)
            budget.get should be > 0L
          }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
