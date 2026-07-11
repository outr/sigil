package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.provider.{Provider, ProviderCall, ProviderEvent, ProviderType}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Coverage for sigil bug #49 — `Provider.maxConcurrent` declaration +
 * `Provider.capacityGate` semaphore. The auto-gating of `Provider.apply`
 * is deferred until stream resource cleanup can guarantee permit
 * release on every termination shape; this spec covers the building
 * blocks the trait exposes today.
 */
class ProviderCapacityGateSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private class CappedProvider(override val maxConcurrent: Int) extends Provider {
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def `type`: ProviderType = ProviderType.OpenAI
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
    override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
      Task.error(new RuntimeException("not implemented"))

    /**
     * Test-only — expose `withCapacity` so we can assert the gate's
     * runtime behaviour against a fake task.
     */
    def public[A](task: Task[A]): Task[A] = withCapacity(task)
  }

  "Provider.maxConcurrent" should {

    "default to Int.MaxValue (no cap)" in {
      object DefaultProv extends Provider {
        override protected def sigil: _root_.sigil.Sigil = TestSigil
        override def `type`: ProviderType = ProviderType.OpenAI
        override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.empty
        override def httpRequestFor(input: ProviderCall): Task[spice.http.HttpRequest] =
          Task.error(new RuntimeException("not implemented"))
      }
      DefaultProv.maxConcurrent shouldBe Int.MaxValue
      Task.unit.map(_ => succeed)
    }

    "honour subclass override of maxConcurrent" in {
      val provider = new CappedProvider(maxConcurrent = 4)
      provider.maxConcurrent shouldBe 4
      Task.unit.map(_ => succeed)
    }

    "expose a Semaphore with maxConcurrent permits" in {
      val provider = new CappedProvider(maxConcurrent = 3)
      provider.capacityGate.availablePermits() shouldBe 3
      Task.unit.map(_ => succeed)
    }
  }

  "Provider.withCapacity" should {

    "release the permit after a Task completes (success path)" in {
      val provider = new CappedProvider(maxConcurrent = 2)
      val before = provider.capacityGate.availablePermits()
      provider.public(Task.pure(42)).map { result =>
        result shouldBe 42
        provider.capacityGate.availablePermits() shouldBe before
      }
    }

    "release the permit after a Task fails (error path)" in {
      val provider = new CappedProvider(maxConcurrent = 2)
      val before = provider.capacityGate.availablePermits()
      provider.public(Task.error[Int](new RuntimeException("boom")))
        .handleError(_ => Task.pure(0))
        .map { _ =>
          provider.capacityGate.availablePermits() shouldBe before
        }
    }

    "permit `maxConcurrent` concurrent invocations and serialize the rest" in {
      val provider = new CappedProvider(maxConcurrent = 2)
      val inFlight = new AtomicInteger(0)
      val peak = new AtomicInteger(0)
      val total = new AtomicInteger(0)

      def task: Task[Unit] = provider.public {
        Task.defer {
          val now = inFlight.incrementAndGet()
          peak.updateAndGet(prev => math.max(prev, now))
          total.incrementAndGet()
          Task.sleep(150.millis)
        }.guarantee(Task { inFlight.decrementAndGet(); () })
      }

      val latch = new java.util.concurrent.CountDownLatch(6)
      (1 to 6).foreach(_ => task.guarantee(Task { latch.countDown(); () }).startUnit())
      Task.sleep(50.millis).flatMap { _ =>
        Task {
          val ok = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
          ok shouldBe true
          ()
        }
      }.map { _ =>
        total.get() shouldBe 6
        peak.get() should be <= 2
        peak.get() should be >= 2
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
