package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.event.{Event, Message, MessageRole, MessageVisibility}
import sigil.signal.{ConversationHistorySnapshot, ConversationSnapshot, EventState, Signal}
import sigil.tool.model.ResponseContent
import sigil.transport.SigilDbEventLog

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * History replay must honor [[MessageVisibility]] exactly as the live
 * wire does. Field failure: a hard refresh mid-turn rendered the
 * framework's internal progress-checkpoint directive (Tool-role,
 * Agents visibility) in the user's chat — the live path filtered it
 * via `canSee`, the history/replay path did not. Every bulk-delivery
 * surface is covered here:
 *
 *   1. `eventsFor(viewer = ...)` — the canonical cold read;
 *   2. the `SwitchConversation` / `RequestConversationHistory`
 *      snapshot handlers (the hard-refresh vocabulary);
 *   3. `SigilDbEventLog` with a bound viewer (durable-socket
 *      cross-restart resume).
 */
class HistoryReplayVisibilitySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def seedConversation(): Task[(Id[Conversation], Message, Message)] = {
    val convId = Conversation.id(s"replay-vis-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val userMsg = Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      content = Vector(ResponseContent.Text("please do the thing")),
      state = EventState.Complete
    )
    // The field shape: an internal checkpoint directive — Agents
    // visibility, never for user UIs.
    val internal = Message(
      participantId = TestAgent,
      conversationId = convId,
      topicId = TestTopicEntry.id,
      role = MessageRole.Standard,
      visibility = MessageVisibility.Agents,
      content = Vector(ResponseContent.Text(
        "[progress checkpoint — internal, not a user message] You have run 40 iterations without meaningful progress."
      )),
      state = EventState.Complete
    )
    for {
      _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      _ <- TestSigil.publish(userMsg)
      _ <- TestSigil.publish(internal)
    } yield (convId, userMsg, internal)
  }

  private def startRecorder(viewer: sigil.participant.ParticipantId)
    : (ConcurrentLinkedQueue[Signal], java.util.concurrent.atomic.AtomicBoolean) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    val running = new java.util.concurrent.atomic.AtomicBoolean(true)
    TestSigil.signalsFor(viewer)
      .takeWhile(_ => running.get())
      .evalMap(s => Task { recorded.add(s); () })
      .drain
      .startUnit()
    (recorded, running)
  }

  private def waitFor(deadlineMs: Long)(pred: => Boolean): Task[Unit] =
    if (pred || System.currentTimeMillis() > deadlineMs) Task.unit
    else Task.sleep(50.millis).flatMap(_ => waitFor(deadlineMs)(pred))

  "eventsFor with a viewer" should {

    "drop Agents-visibility events for a user viewer, keep them for an agent and for raw reads" in {
      for {
        seeded <- seedConversation()
        (convId, userMsg, internal) = seeded
        userView <- TestSigil.eventsFor(convId, viewer = Some(TestUser))
        agentView <- TestSigil.eventsFor(convId, viewer = Some(TestAgent))
        rawView <- TestSigil.eventsFor(convId)
      } yield {
        userView.events.map(_._id) should contain(userMsg._id)
        userView.events.map(_._id) should not contain internal._id
        // The agent legitimately sees internal machinery.
        agentView.events.map(_._id) should contain(internal._id)
        // Framework-internal raw reads (compaction, prompt builds) see everything.
        rawView.events.map(_._id) should contain(internal._id)
      }
    }
  }

  "the history snapshot handlers" should {

    "exclude Agents-visibility events from a user's SwitchConversation snapshot" in {
      for {
        seeded <- seedConversation()
        (convId, userMsg, internal) = seeded
        pair = startRecorder(TestUser)
        (recorded, running) = pair
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.handleNotice(sigil.signal.SwitchConversation(convId, limit = 50), TestUser)
        _ <- waitFor(System.currentTimeMillis() + 5000L)(
          recorded.iterator().asScala.exists(_.isInstanceOf[ConversationSnapshot])
        )
      } yield {
        running.set(false)
        val snapshot = recorded.iterator().asScala.collectFirst {
          case s: ConversationSnapshot if s.conversationId == convId => s
        }.getOrElse(fail("no ConversationSnapshot delivered"))
        snapshot.recentEvents.map(_._id) should contain(userMsg._id)
        snapshot.recentEvents.map(_._id) should not contain internal._id
      }
    }

    "exclude Agents-visibility events from a user's RequestConversationHistory snapshot" in {
      for {
        seeded <- seedConversation()
        (convId, userMsg, internal) = seeded
        pair = startRecorder(TestUser)
        (recorded, running) = pair
        _ <- Task.sleep(150.millis)
        _ <- TestSigil.handleNotice(
          sigil.signal.RequestConversationHistory(convId, beforeMs = System.currentTimeMillis() + 60000L, limit = 50),
          TestUser
        )
        _ <- waitFor(System.currentTimeMillis() + 5000L)(
          recorded.iterator().asScala.exists(_.isInstanceOf[ConversationHistorySnapshot])
        )
      } yield {
        running.set(false)
        val snapshot = recorded.iterator().asScala.collectFirst {
          case s: ConversationHistorySnapshot if s.conversationId == convId => s
        }.getOrElse(fail("no ConversationHistorySnapshot delivered"))
        snapshot.events.map(_._id) should contain(userMsg._id)
        snapshot.events.map(_._id) should not contain internal._id
      }
    }
  }

  "SigilDbEventLog" should {

    "scope replay to the bound viewer, and stay raw when unbound" in {
      for {
        seeded <- seedConversation()
        (convId, userMsg, internal) = seeded
        viewerScoped <- new SigilDbEventLog(TestSigil, viewer = Some(TestUser)).replay(convId, afterSeq = 0L)
        unbound <- new SigilDbEventLog(TestSigil).replay(convId, afterSeq = 0L)
      } yield {
        val viewerIds = viewerScoped.map(_._2).collect { case e: Event => e._id }
        viewerIds should contain(userMsg._id)
        viewerIds should not contain internal._id
        val rawIds = unbound.map(_._2).collect { case e: Event => e._id }
        rawIds should contain(internal._id)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
