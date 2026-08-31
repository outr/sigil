package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.dispatcher.TriggerFilter
import sigil.event.{Event, Message, ReadState}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.signal.{EventState, ReadStateDelta, Signal}
import sigil.tool.model.ResponseContent

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil bug #62 — `ReadState` Event + `ReadStateDelta`
 * with a deterministic id per `(conversationId, participantId)`.
 * Verifies:
 *   1. First `markRead` inserts a ReadState row at the deterministic
 *      id; subsequent advances emit a ReadStateDelta that mutates
 *      the existing row (no `db.events` growth per advance).
 *   2. `markRead(eventId)` resolves the event's authoritative
 *      server timestamp — clients can't accidentally specify
 *      future-time / drifted-clock cursors.
 *   3. `readStateFor` returns the current cursor.
 *   4. The default `TriggerFilter` ignores ReadState — read-cursor
 *      advances do NOT re-fire the agent.
 */
class ReadStateSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def upsertConv(): Task[Conversation] = {
    val convId = Conversation.id(s"read-${rapid.Unique()}")
    val topic = TopicEntry(id = Topic.id(s"topic-$convId"), label = "test", summary = "test")
    val conv = Conversation(_id = convId, topics = List(topic))
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
  }

  /**
   * Capture every signal emitted while `body` runs.
   */
  private def captureSignals[A](body: Task[A]): Task[(A, List[Signal])] = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new atomic.AtomicBoolean(true)
    TestSigil.signals
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    for {
      _ <- Task.sleep(50.millis)
      result <- body
      _ <- Task.sleep(150.millis)
    } yield {
      running.set(false)
      (result, recorded.iterator().asScala.toList)
    }
  }

  "Sigil.markRead" should {

    "insert a ReadState event on first call, with deterministic id" in {
      for {
        conv <- upsertConv()
        ts = Timestamp()
        captured <- captureSignals(TestSigil.markRead(conv._id, TestUser, ts))
        (_, signals) = captured
        listed <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val expectedId = ReadState.idFor(conv._id, TestUser)
        // The Event was published.
        signals.collect { case r: ReadState => r } should have size 1
        signals.collect { case r: ReadState => r }.head._id shouldBe expectedId
        // And persisted at the deterministic id.
        listed.collect { case r: ReadState if r._id == expectedId => r } should have size 1
      }
    }

    "emit a ReadStateDelta on second + subsequent advances (no new event row)" in {
      for {
        conv <- upsertConv()
        first = Timestamp()
        _ <- TestSigil.markRead(conv._id, TestUser, first)
        second = Timestamp()
        captured <- captureSignals(TestSigil.markRead(conv._id, TestUser, second))
        (_, signals) = captured
        listed <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        // Second call emitted a Delta, not a fresh Event.
        val deltas = signals.collect { case d: ReadStateDelta => d }
        deltas should have size 1
        signals.collect { case _: ReadState => () } shouldBe empty
        // Bug #66 — Delta carries participantId so consumers can
        // route per-participant without re-resolving the parent.
        deltas.head.participantId shouldBe TestUser
        deltas.head.conversationId shouldBe conv._id
        deltas.head.lastReadAt shouldBe second
        // Still exactly ONE ReadState row at the deterministic
        // id — Delta UPDATED the existing row, didn't insert.
        val expectedId = ReadState.idFor(conv._id, TestUser)
        val rows = listed.collect { case r: ReadState if r._id == expectedId => r }
        rows should have size 1
        // And `lastReadAt` reflects the second timestamp.
        rows.head.lastReadAt shouldBe second
      }
    }

    "resolve eventId-overload to the event's server-stamped timestamp" in {
      for {
        conv <- upsertConv()
        // Publish a message; its `timestamp` is what we want
        // markRead-by-id to resolve to.
        msg = Message(
          participantId = TestUser,
          conversationId = conv._id,
          topicId = conv.currentTopicId,
          content = Vector(ResponseContent.Text("hi")),
          state = EventState.Complete
        )
        _ <- TestSigil.publish(msg)
        _ <- TestSigil.markRead(conv._id, TestUser, msg._id)
        st <- TestSigil.readStateFor(conv._id, TestUser)
      } yield st.map(_.lastReadAt) shouldBe Some(msg.timestamp)
    }

    "no-op when the eventId-overload references a non-existent event" in {
      for {
        conv <- upsertConv()
        _ <- TestSigil.markRead(conv._id, TestUser, Event.id()) // bogus id
        st <- TestSigil.readStateFor(conv._id, TestUser)
      } yield st shouldBe None
    }
  }

  "Sigil.readStateFor" should {
    "return None before any markRead, Some after" in {
      for {
        conv <- upsertConv()
        before <- TestSigil.readStateFor(conv._id, TestUser)
        _ <- TestSigil.markRead(conv._id, TestUser, Timestamp())
        after <- TestSigil.readStateFor(conv._id, TestUser)
      } yield {
        before shouldBe None
        after.map(_.participantId) shouldBe Some(TestUser)
      }
    }
  }

  "TriggerFilter (bug #62)" should {
    "leave ReadState events as non-triggers" in Task {
      val agent: AgentParticipant = DefaultAgentParticipant(
        id = TestAgent,
        modelId = sigil.db.Model.id("test", "model")
      )
      val convId = Conversation.id("read-trigger-test")
      val topicId = Topic.id("read-trigger-topic")
      val rs = ReadState(
        participantId = TestUser,
        conversationId = convId,
        topicId = topicId,
        lastReadAt = Timestamp()
      )
      TriggerFilter.isTriggerFor(agent, rs) shouldBe false
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
