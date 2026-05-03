package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.Model
import sigil.provider.{
  BuiltInTool, CallId, ErrorClassifier, ErrorClassification, GenerationSettings,
  LoadBalancedProvider, Provider, ProviderCall, ProviderEvent, ProviderType,
  StopReason, ToolChoice
}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for [[LoadBalancedProvider]]. Two paths matter:
 *   1. Round-robin distribution — repeated `apply` calls cycle
 *      through pool members in order.
 *   2. Failover — when a member raises a non-Fatal error, the next
 *      member is tried; Fatal errors propagate immediately.
 *
 * We drive `call` directly (the framework-internal entry) since the
 * pool's behaviour is purely about `ProviderCall → Stream[ProviderEvent]`
 * dispatch — no full `apply` pipeline is needed for these unit tests.
 */
class LoadBalancedProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  /** Counts call-attempts per instance; emits a single Done event with
   * a sentinel `id` so the spec can identify which member ran. */
  private class CountingProvider(label: String) extends Provider {
    val counter: AtomicInteger = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      counter.incrementAndGet()
      val cid = CallId(s"$label-${counter.get()}")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(cid, label),
        ProviderEvent.Done(StopReason.Complete)
      ))
    }
  }

  private class FailingProvider(label: String, err: Throwable) extends Provider {
    val attempts: AtomicInteger = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      attempts.incrementAndGet()
      Stream.force(Task.error(err))
    }
  }

  private val emptyCall: ProviderCall = ProviderCall(
    modelId = Model.id("test", "model"),
    system = "",
    messages = Vector.empty,
    tools = Vector.empty,
    builtInTools = Set.empty,
    toolChoice = ToolChoice.None,
    generationSettings = GenerationSettings()
  )

  "LoadBalancedProvider" should {
    "round-robin across pool members" in {
      val a = new CountingProvider("a")
      val b = new CountingProvider("b")
      val c = new CountingProvider("c")
      val pool = LoadBalancedProvider(Vector(a, b, c), TestSigil)

      def runOnce: Task[Unit] = pool.call(emptyCall).toList.map(_ => ())

      for {
        _ <- runOnce
        _ <- runOnce
        _ <- runOnce
      } yield {
        a.counter.get() shouldBe 1
        b.counter.get() shouldBe 1
        c.counter.get() shouldBe 1
      }
    }

    "fall over to the next pool member when the first fails non-fatally" in {
      val transientErr = new RuntimeException("HTTP 503 service unavailable")
      val failing = new FailingProvider("primary", transientErr)
      val healthy = new CountingProvider("backup")
      val pool = LoadBalancedProvider(Vector(failing, healthy), TestSigil)

      pool.call(emptyCall).toList.map { events =>
        // The failing member was tried; the healthy member served the request
        failing.attempts.get() should be >= 1
        healthy.counter.get() shouldBe 1
        events should not be empty
      }
    }

    "propagate Fatal errors without trying further pool members" in {
      val fatalErr = new RuntimeException("HTTP 401 unauthorized — invalid api key")
      val first = new FailingProvider("first", fatalErr)
      val second = new CountingProvider("second")
      val pool = LoadBalancedProvider(Vector(first, second), TestSigil)

      pool.call(emptyCall).toList.attempt.map { result =>
        result.isFailure shouldBe true
        first.attempts.get() shouldBe 1
        // Fatal stops the chain — second was NOT tried.
        second.counter.get() shouldBe 0
      }
    }

    "raise a clear error when every pool member fails non-fatally" in {
      val transientErr = new RuntimeException("HTTP 503")
      val a = new FailingProvider("a", transientErr)
      val b = new FailingProvider("b", transientErr)
      val pool = LoadBalancedProvider(Vector(a, b), TestSigil)

      pool.call(emptyCall).toList.attempt.map { result =>
        result.isFailure shouldBe true
        a.attempts.get() shouldBe 1
        b.attempts.get() shouldBe 1
        result.failed.get.getMessage should include("every pool member failed")
      }
    }

    "require a non-empty pool" in {
      val ex = intercept[IllegalArgumentException] {
        LoadBalancedProvider(Vector.empty, TestSigil)
      }
      ex.getMessage should include("at least one provider")
      Task.unit.map(_ => succeed)
    }
  }
}
