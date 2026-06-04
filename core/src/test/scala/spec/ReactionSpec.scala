package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.dispatcher.TriggerFilter
import sigil.event.{Event, Message, Reaction}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.signal.{EventState, Signal}
import sigil.tool.model.ResponseContent

import java.util.concurrent.{ConcurrentLinkedQueue, atomic}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil bug #61 — `Reaction` Event subtype with
 * toggle semantics. Verifies:
 *   1. `Sigil.react(...)` publishes a Reaction event that round-
 *      trips through the standard publish pipeline (persists,
 *      broadcasts).
 *   2. `removed = true` is encoded on the same event type, not a
 *      separate Add/Remove protocol.
 *   3. The default `TriggerFilter` ignores Reaction events — an
 *      emoji on an agent's reply does NOT re-fire the agent.
 *   4. Reactions carry no `contextFrame` — they're UI signal, not
 *      curator-visible context.
 */
class ReactionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def upsertConv(): Task[Conversation] = {
    val convId = Conversation.id(s"react-${rapid.Unique()}")
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

  "Sigil.react" should {

    "publish a Reaction event that lands in db.events and broadcasts via the hub" in {
      for {
        conv <- upsertConv()
        msgId = Event.id()
        captured <- captureSignals(TestSigil.react(
          conversationId = conv._id,
          participantId = TestUser,
          messageId = msgId,
          emoji = "👍"
        ))
        (_, signals) = captured
        listed <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val reactions = signals.collect { case r: Reaction => r }
        reactions should have size 1
        reactions.head.emoji shouldBe "👍"
        reactions.head.removed shouldBe false
        reactions.head.messageId shouldBe msgId
        reactions.head.participantId shouldBe TestUser

        // Persisted in the event store like any other event.
        listed.collect { case r: Reaction if r.messageId == msgId => r } should have size 1
      }
    }

    "encode reaction-removal on the SAME Event with `removed = true`" in {
      for {
        conv <- upsertConv()
        msgId = Event.id()
        _ <- TestSigil.react(conv._id, TestUser, msgId, "👍", removed = false)
        _ <- TestSigil.react(conv._id, TestUser, msgId, "👍", removed = true)
        listed <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val tail = listed.collect {
          case r: Reaction if r.messageId == msgId && r.participantId == TestUser && r.emoji == "👍" => r
        }.sortBy(_.timestamp.value)
        tail should have size 2
        tail.head.removed shouldBe false
        tail.last.removed shouldBe true
        // Same Event class — no Add/Remove split.
        tail.head.getClass shouldBe tail.last.getClass
      }
    }

    "carry no contextFrame (curator doesn't render reactions)" in {
      for {
        conv <- upsertConv()
        _ <- TestSigil.react(conv._id, TestUser, Event.id(), "❤️")
        listed <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val r = listed.collect { case r: Reaction => r }.head
        r.contextFrame shouldBe None
      }
    }
  }

  "TriggerFilter (bug #61)" should {

    "leave Reaction events as non-triggers — an emoji doesn't re-fire the agent" in Task {
      val agent: AgentParticipant = DefaultAgentParticipant(
        id = TestAgent,
        modelId = sigil.db.Model.id("test", "model")
      )
      val convId = Conversation.id("trigger-test")
      val topicId = Topic.id("topic-trigger-test")
      val reaction = Reaction(
        participantId = TestUser,
        conversationId = convId,
        topicId = topicId,
        messageId = Event.id(),
        emoji = "🤔"
      )
      TriggerFilter.isTriggerFor(agent, reaction) shouldBe false
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
