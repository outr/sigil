package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.tooling.{DurableJsonRpc, JsonRpcTransportException}

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class DurableJsonRpcSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  // Very small silence window so the tests run fast.
  private val silence: FiniteDuration = 200.millis

  "DurableJsonRpc.issueDurable" should {

    "return the response normally when the future completes inside the silence window" in {
      val activity = new AtomicLong(System.currentTimeMillis())
      val attempts = new AtomicLong(0)
      val task = DurableJsonRpc.issueDurable[String](
        operation = "test/fast",
        silenceWindow = silence,
        pollInterval = 25.millis
      )(activitySource = () => activity.get()) { () =>
        attempts.incrementAndGet()
        CompletableFuture.completedFuture("ok")
      }
      task.map { result =>
        result shouldBe "ok"
        attempts.get() shouldBe 1
      }
    }

    "recover when the first attempt's response is lost (the live wire-log scenario)" in {
      // First call returns a future that never completes — the
      // response was lost on the wire (the BSP/LSP server processed
      // the request, but the response chunk dropped). Second call
      // returns the cached result, completing immediately.
      val activity = new AtomicLong(System.currentTimeMillis())
      val attempts = new AtomicLong(0)
      val task = DurableJsonRpc.issueDurable[String](
        operation = "buildTarget/dependencyModules",
        silenceWindow = silence,
        pollInterval = 25.millis
      )(activitySource = () => activity.get()) { () =>
        val n = attempts.incrementAndGet()
        if (n == 1L) new CompletableFuture[String]() // never completes
        else CompletableFuture.completedFuture("recovered-result")
      }
      task.map { result =>
        result shouldBe "recovered-result"
        attempts.get() shouldBe 2
      }
    }

    "raise JsonRpcTransportException after maxAttempts silence expirations" in {
      val activity = new AtomicLong(System.currentTimeMillis())
      val attempts = new AtomicLong(0)
      val task = DurableJsonRpc.issueDurable[String](
        operation = "buildTarget/dependencyModules",
        silenceWindow = silence,
        maxAttempts = 2,
        pollInterval = 25.millis
      )(activitySource = () => activity.get()) { () =>
        attempts.incrementAndGet()
        new CompletableFuture[String]() // always silent
      }
      task.handleError {
        case e: JsonRpcTransportException => Task.pure(Right(e))
        case other => Task.pure(Left(other))
      }.map {
        case Right(e) =>
          attempts.get() shouldBe 2
          e.operation shouldBe "buildTarget/dependencyModules"
          e.attempts shouldBe 2
          e.silenceWindow shouldBe silence
          e.getMessage should include("no response")
        case Left(other) =>
          fail(s"expected JsonRpcTransportException, got: $other")
        case unexpected: String =>
          fail(s"expected JsonRpcTransportException, got success: $unexpected")
      }
    }

    "reset the silence window on activity (long-but-progressing operations don't trip it)" in {
      // Future doesn't resolve for ~3 silence windows, but
      // `activitySource` updates faster than the silence window —
      // simulating a long BSP operation that's emitting progress
      // notifications continuously. The wrapper must NOT retry
      // while activity is flowing.
      val activity = new AtomicLong(System.currentTimeMillis())
      val attempts = new AtomicLong(0)
      val done = new AtomicBoolean(false)
      val future = new CompletableFuture[String]()
      // Activity ticker running in the background.
      val ticker = Task.defer {
        def loop: Task[Unit] = Task.sleep(50.millis).flatMap { _ =>
          if (done.get()) Task.unit
          else {
            activity.set(System.currentTimeMillis())
            loop
          }
        }
        loop
      }
      ticker.startUnit()
      // Resolve the future after 3 silence windows of background activity.
      Task.sleep(silence * 3).map { _ =>
        future.complete("long-but-progressing")
        ()
      }.startUnit()

      val task = DurableJsonRpc.issueDurable[String](
        operation = "buildTarget/compile",
        silenceWindow = silence,
        pollInterval = 25.millis
      )(activitySource = () => activity.get()) { () =>
        attempts.incrementAndGet()
        future
      }
      task.map { result =>
        done.set(true)
        result shouldBe "long-but-progressing"
        attempts.get() shouldBe 1 // no retry triggered — activity kept the clock fresh
      }
    }

    "propagate normal (non-silence) future failures without retrying" in {
      val activity = new AtomicLong(System.currentTimeMillis())
      val attempts = new AtomicLong(0)
      val task = DurableJsonRpc.issueDurable[String](
        operation = "buildTarget/compile",
        silenceWindow = silence,
        pollInterval = 25.millis
      )(activitySource = () => activity.get()) { () =>
        attempts.incrementAndGet()
        val f = new CompletableFuture[String]()
        f.completeExceptionally(new RuntimeException("real server error"))
        f
      }
      task.handleError(e => Task.pure(Left(e))).map {
        case Left(e: RuntimeException) =>
          e.getMessage shouldBe "real server error"
          attempts.get() shouldBe 1 // no retry — error wasn't silence
        case other =>
          fail(s"expected RuntimeException, got: $other")
      }
    }
  }
}
