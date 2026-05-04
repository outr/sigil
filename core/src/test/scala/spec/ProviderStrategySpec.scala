package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, SpaceId}
import sigil.db.Model
import sigil.provider.{
  AnalysisWork, ClassificationWork, CodingWork, ConversationWork,
  DefaultProviderStrategies, ModelCandidate, ProviderConfig, ProviderStrategy,
  ProviderStrategyRecord, ProviderType, SpaceProviderAssignment, SummarizationWork
}

/**
 * End-to-end coverage for the provider config + strategy + assignment
 * pipeline.
 */
class ProviderStrategySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)
  TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace, TestSpace)))

  "ProviderConfig CRUD" should {
    "round-trip and respect accessibleSpaces on read" in {
      val cfg = ProviderConfig(
        space = TestSpace,
        label = "OpenAI prod",
        providerType = ProviderType.OpenAI,
        apiKeySecretId = Some("openai-key-1")
      )
      for {
        saved   <- TestSigil.saveProviderConfig(cfg)
        loaded  <- TestSigil.getProviderConfig(saved._id, List(TestUser))
        listed  <- TestSigil.listProviderConfigs(TestSpace, List(TestUser))
      } yield {
        loaded shouldBe defined
        loaded.get.label shouldBe "OpenAI prod"
        loaded.get.apiKeySecretId shouldBe Some("openai-key-1")
        listed.exists(_._id == saved._id) shouldBe true
      }
    }

    "return None for fetch when chain isn't authorized" in {
      val cfg = ProviderConfig(space = TestSpace, label = "Hidden",
                               providerType = ProviderType.OpenAI)
      for {
        saved <- TestSigil.saveProviderConfig(cfg)
        _     <- Task { TestSigil.setAccessibleSpaces(_ => Task.pure(Set.empty)) }
        miss  <- TestSigil.getProviderConfig(saved._id, List(TestUser))
        _     <- Task { TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace, TestSpace))) }
      } yield miss shouldBe None
    }
  }

  "ProviderStrategyRecord CRUD + assignment" should {
    "save, list, assign, resolve, and unassign" in {
      val rec = ProviderStrategyRecord(
        space = TestSpace,
        label = "test-strat",
        defaultCandidates = List(ModelCandidate(Id[Model]("openai/gpt-5.4")))
      )
      for {
        saved      <- TestSigil.saveProviderStrategy(rec)
        listed     <- TestSigil.listProviderStrategies(TestSpace, List(TestUser))
        _          <- TestSigil.assignProviderStrategy(TestSpace, saved._id, List(TestUser))
        assigned   <- TestSigil.assignedProviderStrategy(TestSpace)
        resolved   <- TestSigil.resolveProviderStrategy(TestSpace)
        _          <- TestSigil.unassignProviderStrategy(TestSpace, List(TestUser))
        afterUnset <- TestSigil.assignedProviderStrategy(TestSpace)
      } yield {
        listed.exists(_._id == saved._id) shouldBe true
        assigned shouldBe Some(saved._id)
        resolved shouldBe defined
        resolved.get.candidates(ConversationWork).map(_.modelId.value) shouldBe List("openai/gpt-5.4")
        afterUnset shouldBe None
      }
    }

    "delete cascades — orphaned space assignments are cleaned up" in {
      val rec = ProviderStrategyRecord(
        space = TestSpace, label = "to-delete",
        defaultCandidates = List(ModelCandidate(Id[Model]("openai/gpt-5.4-mini")))
      )
      for {
        saved          <- TestSigil.saveProviderStrategy(rec)
        _              <- TestSigil.assignProviderStrategy(TestSpace, saved._id, List(TestUser))
        _              <- TestSigil.deleteProviderStrategy(saved._id, List(TestUser))
        afterDelete    <- TestSigil.assignedProviderStrategy(TestSpace)
        listed         <- TestSigil.listProviderStrategies(TestSpace, List(TestUser))
      } yield {
        afterDelete shouldBe None
        listed.exists(_._id == saved._id) shouldBe false
      }
    }
  }

  "ProviderStrategy.routed" should {
    "return per-work-type candidates with default fallback" in {
      val s = ProviderStrategy.routed(
        default = List(ModelCandidate(Id[Model]("default-model"))),
        routes  = Map(
          CodingWork -> List(ModelCandidate(Id[Model]("coding-model"))),
          ClassificationWork -> List(ModelCandidate(Id[Model]("cheap-model")))
        )
      )
      Task {
        s.candidates(CodingWork).map(_.modelId.value)         shouldBe List("coding-model")
        s.candidates(ClassificationWork).map(_.modelId.value) shouldBe List("cheap-model")
        s.candidates(AnalysisWork).map(_.modelId.value)       shouldBe List("default-model")
        s.candidates(SummarizationWork).map(_.modelId.value)  shouldBe List("default-model")
      }
    }
  }

  "DefaultProviderStrategies" should {
    "seed three preset strategies idempotently" in {
      // Use a fresh space so we don't collide with earlier test seeds.
      case object SeedSpace extends SpaceId { override val value: String = "seed-space" }
      sigil.SpaceId.register(fabric.rw.RW.static[SpaceId](SeedSpace))
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(GlobalSpace, TestSpace, SeedSpace)))
      for {
        first  <- DefaultProviderStrategies.seed(TestSigil, SeedSpace)
        second <- DefaultProviderStrategies.seed(TestSigil, SeedSpace)
      } yield {
        first should have size 3
        first.map(_.label).toSet shouldBe Set("Balanced", "Premium Quality", "Economy")
        // Idempotent — second call returns the same records (same ids).
        second.map(_._id) shouldBe first.map(_._id)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
