package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.provider.{CapacityAcquireTimeoutException, Provider, ProviderCall, ProviderEvent, ProviderType}

import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #57 — `Provider.withCapacity` no longer
 * blocks the calling fiber thread indefinitely when a permit isn't
 * available. The bounded `tryAcquire(timeout)` fails fast with a
 * [[CapacityAcquireTimeoutException]] the agent loop's error
 * handler catches, instead of leaving the agent parked at
 * `thinking` forever.
 */
class CapacityAcquireBoundedSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Provider with `maxConcurrent = 1` and a tight acquire timeout
   * so a stuck holder fails fast in the test rather than padding
   * the suite by 60 seconds.
   */
  private class GatedProvider extends Provider {
    override val maxConcurrent: Int = 1
    override protected def capacityAcquireTimeout: FiniteDuration = 500.millis
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def `type`: ProviderType = ProviderType.OpenAI
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new RuntimeException("not implemented"))

    /**
     * Test-only — expose `withCapacity` so the spec drives it
     * directly.
     */
    def public[A](task: Task[A]): Task[A] = withCapacity(task)
  }

  "Provider.withCapacity" should {

    "fail fast with CapacityAcquireTimeoutException when the permit is held longer than the acquire timeout" in {
      val provider = new GatedProvider
      // Hold the only permit by acquiring it directly. The test
      // never releases it — simulates the parked-holder scenario.
      provider.capacityGate.acquire()

      val start = System.currentTimeMillis()
      provider.public(Task.pure(42))
        .map(v => Right(v): Either[Throwable, Int])
        .handleError(e => Task.pure(Left(e)))
        .map { result =>
          val elapsed = System.currentTimeMillis() - start
          // Within the 500ms acquire timeout (+ generous slack for
          // thread scheduling). Critically NOT indefinite.
          elapsed should be < 2000L
          result match {
            case Right(_) => fail("expected CapacityAcquireTimeoutException, got success")
            case Left(t) =>
              t shouldBe a[CapacityAcquireTimeoutException]
              t.getMessage should include("permit")
          }
        }
    }

    "release the permit normally when the inner task completes (regression guard for the success path)" in {
      val provider = new GatedProvider
      // Capacity available — should run and release.
      provider.public(Task.pure(99)).flatMap { v =>
        Task {
          v shouldBe 99
          // After successful run, the single permit is back.
          provider.capacityGate.availablePermits() shouldBe 1
        }
      }
    }

    "release the permit on inner-task failure (regression guard for the error path)" in {
      val provider = new GatedProvider
      provider.public(Task.error[Int](new RuntimeException("inner boom")))
        .handleError(_ => Task.pure(0))
        .flatMap { _ =>
          Task {
            // Permit must still be back for the next caller.
            provider.capacityGate.availablePermits() shouldBe 1
          }
        }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
