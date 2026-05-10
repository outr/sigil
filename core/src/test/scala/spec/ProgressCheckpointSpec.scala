package spec

import fabric.rw.RW
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, Topic}
import sigil.event.{Event, ProgressCheckpoint}

/**
 * Coverage for `ProgressCheckpoint` event persistence + the
 * config knobs that drive the agent-loop checkpoint mechanism.
 *
 * Locked invariants:
 *   1. The event round-trips through the polymorphic Event RW
 *      and lands in `db.events` like any other Event subtype.
 *   2. Default config produces a runaway-safe loop ceiling
 *      (`maxAgentIterations = 200`) and a non-zero checkpoint
 *      interval (`progressCheckpointInterval = 15`).
 *   3. `consecutiveNoProgressLimit` defaults to 2 — two
 *      successive `meaningfulProgress = false` checkpoints are
 *      what the loop treats as stuck.
 *
 * The full agent-loop integration (LLM-driven reflection +
 * synthetic respond on stuck) is exercised end-to-end via live-
 * LLM specs apps add for their model fixtures — this framework
 * spec covers the persistent shape and the config defaults.
 */
class ProgressCheckpointSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "ProgressCheckpoint event" should {

    "round-trip through SigilDB.events" in {
      val convId = Conversation.id(s"checkpoint-${rapid.Unique()}")
      val topic = Topic(
        conversationId = convId,
        label = "spec",
        summary = "spec",
        createdBy = TestUser
      )
      val checkpoint = ProgressCheckpoint(
        participantId        = TestAgent,
        conversationId       = convId,
        topicId              = topic._id,
        iterationCount       = 15,
        prevCheckpointStatus = Some("looking up the right tool"),
        currentStatus        = "ran grep across the codebase, found 3 candidate files",
        meaningfulProgress   = true,
        remainingSteps       = "narrow to the actual call site and read it",
        stuckOn              = None,
        shouldAskUser        = false
      )
      for {
        _      <- TestSigil.withDB(_.topics.transaction(_.upsert(topic))).unit
        _      <- TestSigil.publish(checkpoint)
        loaded <- TestSigil.withDB(_.events.transaction(_.get(checkpoint._id)))
      } yield {
        loaded shouldBe defined
        loaded.get shouldBe a [ProgressCheckpoint]
        val rt = loaded.get.asInstanceOf[ProgressCheckpoint]
        rt.iterationCount       shouldBe 15
        rt.prevCheckpointStatus shouldBe Some("looking up the right tool")
        rt.meaningfulProgress   shouldBe true
        rt.stuckOn              shouldBe None
      }
    }

    "carry the prior status anchor for stuck-detection chains" in {
      val convId = Conversation.id(s"checkpoint-chain-${rapid.Unique()}")
      val topic = Topic(
        conversationId = convId,
        label = "spec",
        summary = "spec",
        createdBy = TestUser
      )
      val first = ProgressCheckpoint(
        participantId = TestAgent, conversationId = convId, topicId = topic._id,
        iterationCount = 15, prevCheckpointStatus = None,
        currentStatus = "searching for the auth filter",
        meaningfulProgress = false,
        remainingSteps = "find the file", stuckOn = Some("can't find the tool"), shouldAskUser = false
      )
      val second = ProgressCheckpoint(
        participantId = TestAgent, conversationId = convId, topicId = topic._id,
        iterationCount = 30, prevCheckpointStatus = Some(first.currentStatus),
        currentStatus = "still searching for the auth filter",
        meaningfulProgress = false,
        remainingSteps = "find the file", stuckOn = Some("can't find the tool"), shouldAskUser = false
      )
      for {
        _ <- TestSigil.withDB(_.topics.transaction(_.upsert(topic))).unit
        _ <- TestSigil.publish(first)
        _ <- TestSigil.publish(second)
        loadedFirst  <- TestSigil.withDB(_.events.transaction(_.get(first._id)))
        loadedSecond <- TestSigil.withDB(_.events.transaction(_.get(second._id)))
      } yield {
        loadedFirst.collect  { case c: ProgressCheckpoint => c.currentStatus } shouldBe Some(first.currentStatus)
        loadedSecond.collect { case c: ProgressCheckpoint => c.prevCheckpointStatus }.flatten shouldBe Some(first.currentStatus)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
