package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextFrame, Conversation, FrameBuilder}
import sigil.event.{Message, MessageVisibility}
import sigil.signal.{EventState, Signal, StateDelta}
import sigil.transport.{ResumeRequest, SignalTransport}
import sigil.tool.model.ResponseContent

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Coverage for the [[MessageVisibility]] scope rule on both
 * enforcement points: `Sigil.canSee` (wire delivery via
 * `signalsFor`) and per-frame filtering in `SignalTransport.replay`.
 */
class MessageVisibilitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id("visibility-conv")

  private def msg(text: String, vis: MessageVisibility): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      visibility = vis
    )

  "Sigil.canSee" should {
    "pass MessageVisibility.All to any viewer" in Task {
      val m = msg("public", MessageVisibility.All)
      TestSigil.canSee(m, TestUser) shouldBe true
      TestSigil.canSee(m, TestAgent) shouldBe true
    }

    "pass MessageVisibility.Agents only to AgentParticipantId viewers" in Task {
      val m = msg("internal", MessageVisibility.Agents)
      TestSigil.canSee(m, TestAgent) shouldBe true
      TestSigil.canSee(m, TestUser) shouldBe false
    }

    "pass MessageVisibility.Users only to non-Agent viewers" in Task {
      val m = msg("user-facing", MessageVisibility.Users)
      TestSigil.canSee(m, TestUser) shouldBe true
      TestSigil.canSee(m, TestAgent) shouldBe false
    }

    "pass MessageVisibility.Participants(ids) only to listed viewers" in Task {
      val m = msg("scoped", MessageVisibility.Participants(Set(TestUser)))
      TestSigil.canSee(m, TestUser) shouldBe true
      TestSigil.canSee(m, TestAgent) shouldBe false
    }

    "pass non-Event signals (Deltas) regardless of viewer" in Task {
      // Deltas describe in-flight state and don't carry visibility — the
      // client UI is expected to ignore deltas whose target event was
      // filtered out at the wire boundary.
      val m = msg("hidden parent", MessageVisibility.Agents)
      val delta = StateDelta(target = m._id, conversationId = convId, state = EventState.Complete)
      TestSigil.canSee(delta, TestUser) shouldBe true
    }
  }

  "FrameBuilder" should {
    "denormalize the source Message's visibility into the Text frame" in Task {
      val m = msg("scratch", MessageVisibility.Agents)
      val frames = FrameBuilder.appendFor(Vector.empty, m)
      frames should have size 1
      frames.head.visibility shouldBe MessageVisibility.Agents
    }

    "default frames built from non-visibility-tagged events to All" in Task {
      val m = msg("public", MessageVisibility.All)
      val frames = FrameBuilder.appendFor(Vector.empty, m)
      frames.head.visibility shouldBe MessageVisibility.All
    }
  }

  "signalsFor(viewer)" should {
    "drop Agents-only events for a non-agent viewer" in {
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true

      // Subscribe before publishing so the hub buffers signals into our queue.
      TestSigil.signalsFor(TestUser)
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()

      val publicMsg   = msg("public",   MessageVisibility.All)
      val internalMsg = msg("internal", MessageVisibility.Agents)

      for {
        _ <- TestSigil.publish(publicMsg)
        _ <- TestSigil.publish(internalMsg)
        _ <- Task.sleep(100.millis)
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val seen = recorded.iterator().asScala.toList.collect { case m: Message => m._id }
        seen should contain(publicMsg._id)
        seen should not contain internalMsg._id
      }
    }

    "deliver Agents-only events to an agent viewer" in {
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true

      TestSigil.signalsFor(TestAgent)
        .evalMap(s => Task { recorded.add(s); () })
        .takeWhile(_ => running)
        .drain
        .startUnit()

      val internalMsg = msg("internal", MessageVisibility.Agents)

      for {
        _ <- TestSigil.publish(internalMsg)
        _ <- Task.sleep(100.millis)
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val seen = recorded.iterator().asScala.toList.collect { case m: Message => m._id }
        seen should contain(internalMsg._id)
      }
    }
  }

  "SignalTransport.replay" should {
    "filter Agents-only events from history for a non-agent viewer" in {
      val transport = new SignalTransport(TestSigil)
      val publicMsg   = msg("public-replay",   MessageVisibility.All)
      val internalMsg = msg("internal-replay", MessageVisibility.Agents)

      for {
        _      <- TestSigil.withDB(_.events.transaction(tx =>
                    tx.insert(publicMsg).flatMap(_ => tx.insert(internalMsg))))
        seen   <- transport.replay(TestUser, ResumeRequest.RecentMessages(10), Some(Set(convId)))
                    .toList
                    .map(_.collect { case m: Message => m._id })
      } yield {
        seen should contain(publicMsg._id)
        seen should not contain internalMsg._id
      }
    }

    "preserve Agents-only events for an agent viewer" in {
      val transport = new SignalTransport(TestSigil)
      val internalMsg = msg("internal-agent", MessageVisibility.Agents)

      for {
        _    <- TestSigil.withDB(_.events.transaction(_.insert(internalMsg)))
        seen <- transport.replay(TestAgent, ResumeRequest.RecentMessages(10), Some(Set(convId)))
                  .toList
                  .map(_.collect { case m: Message => m._id })
      } yield {
        seen should contain(internalMsg._id)
      }
    }
  }


  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
