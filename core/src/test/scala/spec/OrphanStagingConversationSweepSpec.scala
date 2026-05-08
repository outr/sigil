package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.maintenance.OrphanStagingConversationSweep

import scala.concurrent.duration.*

/**
 * Coverage for [[OrphanStagingConversationSweep]] — the maintenance
 * task that GCs staging conversations left behind by crashed
 * imports. Verifies:
 *   1. Stale staging convs (older than `cutoff`) get reaped.
 *   2. Fresh staging convs are preserved (legit in-progress imports).
 *   3. Non-staging convs are never touched.
 */
class OrphanStagingConversationSweepSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def upsertConv(c: Conversation): Task[Conversation] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(c)))

  "OrphanStagingConversationSweep" should {

    "delete staging conversations older than cutoff" in {
      // Tight cutoff so the spec runs fast.
      val sweep = OrphanStagingConversationSweep(interval = 1.hour, cutoff = 100.millis)
      val target = Conversation.id(s"target-${rapid.Unique()}")
      val stale  = Conversation.id(s"stale-${rapid.Unique()}")

      val staleConv = Conversation(
        _id        = stale,
        topics     = Nil,
        stagingFor = Some(target),
        // Pre-date the row so it falls past the 100ms cutoff
        // immediately.
        created    = Timestamp(System.currentTimeMillis() - 5000)
      )
      for {
        _ <- upsertConv(staleConv)
        _ <- sweep.runOnce(TestSigil)
        after <- TestSigil.withDB(_.conversations.transaction(_.get(stale)))
      } yield {
        after shouldBe None
      }
    }

    "leave fresh staging conversations alone" in {
      val sweep = OrphanStagingConversationSweep(interval = 1.hour, cutoff = 1.hour)
      val target = Conversation.id(s"target-${rapid.Unique()}")
      val fresh  = Conversation.id(s"fresh-${rapid.Unique()}")
      val freshConv = Conversation(
        _id        = fresh,
        topics     = Nil,
        stagingFor = Some(target),
        created    = Timestamp() // now → well within the 1-hour cutoff
      )
      for {
        _     <- upsertConv(freshConv)
        _     <- sweep.runOnce(TestSigil)
        after <- TestSigil.withDB(_.conversations.transaction(_.get(fresh)))
      } yield after.map(_._id) shouldBe Some(fresh)
    }

    "never touch non-staging conversations even when they're old" in {
      val sweep = OrphanStagingConversationSweep(interval = 1.hour, cutoff = 100.millis)
      val regular = Conversation.id(s"regular-${rapid.Unique()}")
      val regularConv = Conversation(
        _id     = regular,
        topics  = Nil,
        // No stagingFor — this is a normal conversation that
        // happens to be old.
        created = Timestamp(System.currentTimeMillis() - 5000)
      )
      for {
        _     <- upsertConv(regularConv)
        _     <- sweep.runOnce(TestSigil)
        after <- TestSigil.withDB(_.conversations.transaction(_.get(regular)))
      } yield after.map(_._id) shouldBe Some(regular)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
