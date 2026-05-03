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
  }
}
