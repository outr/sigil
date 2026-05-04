package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextMemory, MemorySource, MemoryStatus, MemoryType, UpsertMemoryResult}

/**
 * Mechanical coverage for `Sigil.upsertMemoryByKey` — the versioning
 * helper. Exercises the three branches: first insert, unchanged
 * refresh, and content change (archive + new version).
 */
class ContextMemoryVersioningSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val Space = TestSpace
  private val Key = "pref.language"

  private def seed(fact: String,
                   key: String = Key,
                   label: String = "Versioning seed",
                   summary: String = "test summary"): ContextMemory =
    ContextMemory(
      fact = fact,
      label = label,
      summary = summary,
      source = MemorySource.Explicit,
      spaceId = Space,
      key = if (key.isEmpty) None else Some(key),
      memoryType = MemoryType.Preference
    )

  "Sigil.upsertMemoryByKey" should {
    "reject an empty key" in {
      TestSigil.upsertMemoryByKey(seed("x", key = "")).attempt.map { attempted =>
        attempted.isFailure should be(true)
      }
    }

    "insert the first record with validFrom populated" in {
      val key = "pref.lang.insert"
      for {
        result <- TestSigil.upsertMemoryByKey(seed("Scala", key))
      } yield {
        result shouldBe a[UpsertMemoryResult.Stored]
        val stored = result.memory
        stored.validFrom should not be empty
        stored.validUntil shouldBe empty
        stored.supersedes shouldBe empty
        stored.supersededBy shouldBe empty
      }
    }

    "refresh metadata without creating a new version when content is unchanged" in {
      val key = "pref.lang.refresh"
      for {
        first <- TestSigil.upsertMemoryByKey(seed("Scala", key, label = "v1"))
        second <- TestSigil.upsertMemoryByKey(seed("Scala", key, label = "v2"))
        history <- TestSigil.memoryHistory(key, Space)
      } yield {
        second shouldBe a[UpsertMemoryResult.Refreshed]
        second.memory._id shouldBe first.memory._id
        second.memory.label shouldBe "v2"
        history.size shouldBe 1
      }
    }

    "archive the prior and insert a new version when content changes" in {
      val key = "pref.lang.change"
      for {
        first <- TestSigil.upsertMemoryByKey(seed("Scala", key))
        second <- TestSigil.upsertMemoryByKey(seed("Rust", key))
        history <- TestSigil.memoryHistory(key, Space)
      } yield {
        second shouldBe a[UpsertMemoryResult.Versioned]
        history.size shouldBe 2
        second.memory._id should not equal first.memory._id
        second.memory.supersedes shouldBe Some(first.memory._id)
        second.memory.validFrom should not be empty
        second.memory.validUntil shouldBe empty

        val archived = history.find(_._id == first.memory._id).get
        archived.validUntil should not be empty
        archived.supersededBy shouldBe Some(second.memory._id)
      }
    }
  }

  "Sigil.forgetMemory" should {
    "delete every version of a keyed memory" in {
      val key = "pref.lang.forget"
      for {
        _ <- TestSigil.upsertMemoryByKey(seed("Scala", key))
        _ <- TestSigil.upsertMemoryByKey(seed("Rust", key))
        pre <- TestSigil.memoryHistory(key, Space)
        removed <- TestSigil.forgetMemory(key, Space)
        post <- TestSigil.memoryHistory(key, Space)
      } yield {
        pre.size shouldBe 2
        removed shouldBe 2
        post shouldBe empty
      }
    }
  }

  "Sigil.recordMemoryAccess" should {
    "bump accessCount and update lastAccessedAt" in {
      val key = "pref.lang.access"
      for {
        stored <- TestSigil.upsertMemoryByKey(seed("Scala", key))
        _ <- TestSigil.recordMemoryAccess(stored.memory._id)
        _ <- TestSigil.recordMemoryAccess(stored.memory._id)
        after <- TestSigil.memoryHistory(key, Space)
      } yield {
        after.size shouldBe 1
        after.head.accessCount shouldBe 2
      }
    }
  }

  "Sigil.approveMemory / rejectMemory" should {
    "transition Pending → Approved → Rejected" in {
      val key = "pref.lang.approve"
      val pending = seed("Scala", key).copy(status = MemoryStatus.Pending)
      for {
        stored <- TestSigil.upsertMemoryByKey(pending)
        approved <- TestSigil.approveMemory(stored.memory._id)
        rejected <- TestSigil.rejectMemory(stored.memory._id)
      } yield {
        approved.map(_.status) shouldBe Some(MemoryStatus.Approved)
        rejected.map(_.status) shouldBe Some(MemoryStatus.Rejected)
      }
    }

    "surface pending memories via listPendingMemories" in {
      val key = "pref.lang.pending"
      val pending = seed("Pending fact", key).copy(status = MemoryStatus.Pending)
      for {
        stored <- TestSigil.upsertMemoryByKey(pending)
        listed <- TestSigil.listPendingMemories(Set(Space))
        _ <- TestSigil.approveMemory(stored.memory._id)
        listedAfter <- TestSigil.listPendingMemories(Set(Space))
      } yield {
        listed.map(_._id) should contain(stored.memory._id)
        listedAfter.map(_._id) should not contain stored.memory._id
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
