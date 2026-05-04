package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextMemory, MemorySource, MemoryType}
import sigil.conversation.compression.StandardMemoryRetriever

/**
 * Coverage for the [[ContextMemory.expiresAt]] field added by the
 * memory-write-metadata bundle. Two paths matter:
 *
 *   1. Pure predicate — `StandardMemoryRetriever.isExpired` returns
 *      true iff the record carries an `expiresAt` and that timestamp
 *      is at or before `now`. Records with `expiresAt = None` never
 *      expire, regardless of how old they are.
 *
 *   2. Surfacing path — a memory persisted with an expiry in the past
 *      is invisible to the agent: the curator's resolution drops it
 *      whether it was in the criticals bucket or the retrieved-similar
 *      bucket. We assert the predicate here; the surfacing path is
 *      exercised by [[ContextMemoryVersioningSpec]] and the wider
 *      curator specs once those records are wired through.
 *
 * The `justification` field round-trips automatically via fabric's
 * derived RW — no specific test path needed beyond the persistence
 * specs that already exercise full ContextMemory writes.
 */
class MemoryExpirySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val Space = TestSpace

  private def make(expiresAt: Option[Timestamp] = None,
                   justification: Option[String] = None): ContextMemory =
    ContextMemory(
      fact = "user prefers concise replies",
      label = "Concise replies",
      summary = "User prefers concise replies.",
      source = MemorySource.Explicit,
      spaceId = Space,
      memoryType = MemoryType.Preference,
      expiresAt = expiresAt,
      justification = justification
    )

  "StandardMemoryRetriever.isExpired" should {
    val now = Timestamp(1_000_000L)

    "return false when expiresAt is None" in {
      StandardMemoryRetriever.isExpired(make(), now) shouldBe false
    }

    "return false when expiresAt is in the future" in {
      StandardMemoryRetriever.isExpired(make(expiresAt = Some(Timestamp(now.value + 60_000))), now) shouldBe false
    }

    "return true when expiresAt is in the past" in {
      StandardMemoryRetriever.isExpired(make(expiresAt = Some(Timestamp(now.value - 1_000))), now) shouldBe true
    }

    "return true when expiresAt equals now (boundary inclusive)" in {
      StandardMemoryRetriever.isExpired(make(expiresAt = Some(now)), now) shouldBe true
    }
  }

  "ContextMemory" should {
    "round-trip the justification field" in {
      val key = "pref.justification.roundtrip"
      val seed = make(justification = Some("inferred from explicit user request 2026-05-02"))
        .copy(key = Some(key))
      for {
        _       <- TestSigil.upsertMemoryByKey(seed)
        history <- TestSigil.memoryHistory(key, Space)
        loaded  = history.lastOption
      } yield {
        loaded.flatMap(_.justification) shouldBe Some("inferred from explicit user request 2026-05-02")
      }
    }

    "round-trip the expiresAt field" in {
      val key = "pref.expiresat.roundtrip"
      val expiry = Timestamp(System.currentTimeMillis() + 86_400_000L) // tomorrow
      val seed = make(expiresAt = Some(expiry)).copy(key = Some(key))
      for {
        _       <- TestSigil.upsertMemoryByKey(seed)
        history <- TestSigil.memoryHistory(key, Space)
        loaded  = history.lastOption
      } yield {
        loaded.flatMap(_.expiresAt).map(_.value) shouldBe Some(expiry.value)
      }
    }
  }

  "Sigil.sweepExpiredMemories" should {
    "hard-delete records whose expiresAt is at or before now, leaving live + non-expiring rows" in {
      val now = Timestamp()
      val past = Timestamp(now.value - 60_000L)
      val future = Timestamp(now.value + 60_000L)
      val expired1 = make(expiresAt = Some(past)).copy(key = Some("sweep.expired.1"), fact = "expired one")
      val expired2 = make(expiresAt = Some(past)).copy(key = Some("sweep.expired.2"), fact = "expired two")
      val live = make(expiresAt = Some(future)).copy(key = Some("sweep.live"), fact = "still valid")
      val nonExpiring = make().copy(key = Some("sweep.permanent"), fact = "no expiry")
      for {
        _       <- TestSigil.upsertMemoryByKey(expired1)
        _       <- TestSigil.upsertMemoryByKey(expired2)
        _       <- TestSigil.upsertMemoryByKey(live)
        _       <- TestSigil.upsertMemoryByKey(nonExpiring)
        removed <- TestSigil.sweepExpiredMemories(now)
        survivors <- TestSigil.findMemories(Set(Space)).map(_.flatMap(_.key).toSet)
      } yield {
        removed shouldBe 2
        survivors should contain("sweep.live")
        survivors should contain("sweep.permanent")
        survivors should not contain "sweep.expired.1"
        survivors should not contain "sweep.expired.2"
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
