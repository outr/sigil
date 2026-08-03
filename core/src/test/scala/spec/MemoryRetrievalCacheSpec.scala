package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ContextMemory}
import sigil.conversation.compression.{MemoryRetrievalCache, MemoryRetrievalResult}

import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for [[MemoryRetrievalCache]] — the inter-message-stable
 * cache that keeps non-critical memory retrieval consistent across
 * the agent's iteration burst. Direct unit tests; no Sigil instance
 * needed.
 */
class MemoryRetrievalCacheSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def emptyResult: MemoryRetrievalResult =
    MemoryRetrievalResult(memories = Vector.empty, criticalMemories = Vector.empty)

  private def withMems(ids: String*): MemoryRetrievalResult =
    MemoryRetrievalResult(
      memories = ids.iterator.map(s => Id[ContextMemory](s)).toVector,
      criticalMemories = Vector.empty
    )

  "MemoryRetrievalCache" should {
    "compute on first request and reuse on subsequent requests" in {
      val cache = new MemoryRetrievalCache
      val convId = Conversation.id("conv-cache-1")
      val callCount = new AtomicInteger(0)
      val compute = Task { callCount.incrementAndGet(); withMems("a", "b") }

      for {
        first <- cache.getOrCompute(convId, compute)
        _      = callCount.get() shouldBe 1
        second <- cache.getOrCompute(convId, compute)
      } yield {
        callCount.get() shouldBe 1 // compute did NOT run again
        first.memories.map(_.value) shouldBe Vector("a", "b")
        second shouldBe first
      }
    }

    "recompute after invalidate" in {
      val cache = new MemoryRetrievalCache
      val convId = Conversation.id("conv-cache-2")
      val callCount = new AtomicInteger(0)
      def compute(version: Int) = Task {
        callCount.incrementAndGet()
        withMems(s"v$version-a", s"v$version-b")
      }

      for {
        first <- cache.getOrCompute(convId, compute(1))
        _      = first.memories.map(_.value) shouldBe Vector("v1-a", "v1-b")
        _      = cache.invalidate(convId)
        second <- cache.getOrCompute(convId, compute(2))
      } yield {
        callCount.get() shouldBe 2 // compute ran twice
        second.memories.map(_.value) shouldBe Vector("v2-a", "v2-b")
      }
    }

    "scope cache entries per conversation" in {
      val cache = new MemoryRetrievalCache
      val convA = Conversation.id("conv-a")
      val convB = Conversation.id("conv-b")
      for {
        _ <- cache.getOrCompute(convA, Task.pure(withMems("a-1")))
        _ <- cache.getOrCompute(convB, Task.pure(withMems("b-1")))
        peekA = cache.peek(convA)
        peekB = cache.peek(convB)
      } yield {
        peekA.map(_.memories.map(_.value)) shouldBe Some(Vector("a-1"))
        peekB.map(_.memories.map(_.value)) shouldBe Some(Vector("b-1"))
        // Invalidating one doesn't touch the other.
        cache.invalidate(convA)
        cache.peek(convA) shouldBe None
        cache.peek(convB).map(_.memories.map(_.value)) shouldBe Some(Vector("b-1"))
      }
    }

    "invalidate of a missing conversation is idempotent" in Task {
      val cache = new MemoryRetrievalCache
      noException should be thrownBy cache.invalidate(Conversation.id("nope"))
      noException should be thrownBy cache.invalidate(Conversation.id("nope"))
      cache.peek(Conversation.id("nope")) shouldBe None
    }

    "clear drops every entry" in {
      val cache = new MemoryRetrievalCache
      val a = Conversation.id("conv-clear-a")
      val b = Conversation.id("conv-clear-b")
      for {
        _ <- cache.getOrCompute(a, Task.pure(emptyResult))
        _ <- cache.getOrCompute(b, Task.pure(emptyResult))
      } yield {
        cache.peek(a) shouldBe Symbol("defined")
        cache.peek(b) shouldBe Symbol("defined")
        cache.clear()
        cache.peek(a) shouldBe None
        cache.peek(b) shouldBe None
      }
    }

    "stale every conversation's entry on invalidateAll" in {
      val cache = new MemoryRetrievalCache
      val a = Conversation.id("conv-epoch-a")
      val b = Conversation.id("conv-epoch-b")
      val calls = new AtomicInteger(0)
      def compute(version: Int) = Task { calls.incrementAndGet(); withMems(s"v$version") }
      for {
        _ <- cache.getOrCompute(a, compute(1))
        _ <- cache.getOrCompute(b, compute(1))
        _  = calls.get() shouldBe 2
        _  = cache.invalidateAll()
        _  = cache.peek(a) shouldBe None
        _  = cache.peek(b) shouldBe None
        reA <- cache.getOrCompute(a, compute(2))
        reB <- cache.getOrCompute(b, compute(2))
        // Post-bump entries stay hot: the epoch invalidates once, not forever.
        again <- cache.getOrCompute(a, compute(3))
      } yield {
        calls.get() shouldBe 4
        reA.memories.map(_.value) shouldBe Vector("v2")
        reB.memories.map(_.value) shouldBe Vector("v2")
        again shouldBe reA
      }
    }

    "compute once when concurrent misses race for the same conversation" in {
      val cache = new MemoryRetrievalCache
      val convId = Conversation.id("conv-inflight")
      val calls = new AtomicInteger(0)
      val started = new java.util.concurrent.CountDownLatch(1)
      // The compute blocks until every racer has entered getOrCompute,
      // so a get-then-compute-then-put cache would run it N times.
      val compute = Task {
        calls.incrementAndGet()
        started.await(5, java.util.concurrent.TimeUnit.SECONDS)
        withMems("shared")
      }
      for {
        fibers  <- Task.sequence((1 to 8).toList.map(_ => cache.getOrCompute(convId, compute).start))
        _       <- Task(started.countDown())
        results <- Task.sequence(fibers.map(_.join))
      } yield {
        calls.get() shouldBe 1
        results.foreach(_.memories.map(_.value) shouldBe Vector("shared"))
        succeed
      }
    }

    "bound the entry count and evict the least-recently-computed" in {
      val cache = new MemoryRetrievalCache(maxEntries = 4)
      val ids = (1 to 10).toList.map(i => Conversation.id(s"conv-bound-$i"))
      Task.sequence(ids.map(id => cache.getOrCompute(id, Task.pure(withMems(id.value))))).map { _ =>
        cache.size should be <= 4
        // The newest writes survive; the oldest were evicted.
        cache.peek(ids.last) shouldBe Symbol("defined")
        cache.peek(ids.head) shouldBe None
      }
    }

    "release the in-flight slot when a compute fails so the next call retries" in {
      val cache = new MemoryRetrievalCache
      val convId = Conversation.id("conv-inflight-fail")
      val calls = new AtomicInteger(0)
      for {
        failed <- cache.getOrCompute(convId, Task {
                    calls.incrementAndGet()
                    throw new RuntimeException("retrieval boom")
                  }).attempt
        second <- cache.getOrCompute(convId, Task { calls.incrementAndGet(); withMems("recovered") })
      } yield {
        failed.isFailure shouldBe true
        calls.get() shouldBe 2
        second.memories.map(_.value) shouldBe Vector("recovered")
      }
    }
  }
}
