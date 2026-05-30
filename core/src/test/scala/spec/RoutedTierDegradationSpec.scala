package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.db.Model
import sigil.provider.{Complexity, ModelCandidate, ProviderStrategy, SummarizationWork}

/**
 * Sigil #315 — `routedModelFor` degrades to the nearest *available*
 * tier at or below the requested complexity (High → Medium → Low)
 * instead of cratering to the pinned fallback when the exact tier has
 * no candidate. Down-only: a High request never escalates up to a
 * VeryHigh candidate.
 */
class RoutedTierDegradationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val llama: Id[Model] = Id[Model]("test/tier-llama-low")
  private val haiku: Id[Model] = Id[Model]("test/tier-haiku-medium")
  private val sonnet: Id[Model] = Id[Model]("test/tier-sonnet-high")
  private val opus: Id[Model]  = Id[Model]("test/tier-opus-veryhigh")
  // Pinned fallback, deliberately NOT in any chain, so a test asserting
  // a chain model proves degradation rather than coinciding with fallback.
  private val pinned: Id[Model] = Id[Model]("test/tier-pinned-fallback")

  private def candidate(id: Id[Model], tier: Complexity): ModelCandidate =
    ModelCandidate(id, supportedComplexity = Set(tier))

  /** Wire a SummarizationWork chain into the test space and run `body`
    * with the strategy in hand (for cooldown control). */
  private def withChain[T](chain: List[ModelCandidate])(body: ProviderStrategy => Task[T]): Task[T] = {
    TestSigil.setAccessibleSpaces(_ => Task.pure(Set(TestSpace)))
    val strategy = ProviderStrategy.routed(
      default = chain,
      routes = Map(SummarizationWork -> chain)
    )
    TestSigil.setResolveProviderStrategy(_ => Task.pure(Some(strategy)))
    body(strategy).guarantee(Task(TestSigil.reset()))
  }

  private def route(complexity: Complexity): Task[Id[Model]] =
    TestSigil.routedModelFor(
      SummarizationWork,
      chain = List(TestUser, TestAgent),
      fallback = pinned,
      complexity = Some(complexity)
    )

  "Sigil.routedModelFor tier degradation (#315)" should {

    "degrade High → Medium when no High candidate exists but Medium is available" in {
      withChain(List(candidate(llama, Complexity.Low), candidate(haiku, Complexity.Medium), candidate(opus, Complexity.VeryHigh))) { _ =>
        route(Complexity.High).map(_ shouldBe haiku)
      }
    }

    "pick the exact tier when a High candidate is present (no degradation)" in {
      withChain(List(candidate(llama, Complexity.Low), candidate(haiku, Complexity.Medium), candidate(sonnet, Complexity.High))) { _ =>
        route(Complexity.High).map(_ shouldBe sonnet)
      }
    }

    "degrade past a cooled-down Medium to Low" in {
      withChain(List(candidate(llama, Complexity.Low), candidate(haiku, Complexity.Medium))) { strategy =>
        strategy.reportFailure(haiku, SummarizationWork) // Medium enters cooldown
        route(Complexity.High).map(_ shouldBe llama)
      }
    }

    "fall back when nothing at or below the requested tier is available" in {
      // Only VeryHigh available; request Low — VeryHigh is ABOVE Low, so
      // down-only degradation finds nothing ≤ Low.
      withChain(List(candidate(opus, Complexity.VeryHigh))) { _ =>
        route(Complexity.Low).map(_ shouldBe pinned)
      }
    }

    "not escalate up: High requested, only VeryHigh available → fallback, not VeryHigh" in {
      withChain(List(candidate(opus, Complexity.VeryHigh))) { _ =>
        route(Complexity.High).map(_ shouldBe pinned) // NOT opus
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
