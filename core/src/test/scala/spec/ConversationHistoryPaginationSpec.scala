package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ConversationView}
import sigil.event.{Message, MessageVisibility, MessageRole}
import sigil.signal.{ConversationHistorySnapshot, ConversationSnapshot, EventState, RequestConversationHistory, Signal, SwitchConversation}
import sigil.tool.model.ResponseContent

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Regression for bug #34 — `SwitchConversation` used to ship the
 * entire conversation history in a single `ConversationSnapshot`.
 * The framework now bounds the snapshot to the most recent N
 * events, sets `hasMore = true` when older events exist, and
 * exposes a `RequestConversationHistory` Notice for paging back.
 */
class ConversationHistoryPaginationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  /** Build N messages with strictly increasing timestamps, persist
    * them, and persist a placeholder ConversationView so the
    * SwitchConversation arm finds something to reply with. */
  private def seedConversation(convId: Id[Conversation], count: Int): Task[Vector[Long]] = {
    val baseMs = 1_000_000L
    val msgs = (0 until count).map { i =>
      Message(
        participantId  = TestUser,
        conversationId = convId,
        topicId        = TestTopicId,
        content        = Vector(ResponseContent.Text(s"msg-$i")),
        state          = EventState.Complete,
        role           = MessageRole.Standard,
        visibility     = MessageVisibility.All,
        timestamp      = Timestamp(baseMs + i)
      )
    }.toVector
    val view = ConversationView(conversationId = convId, _id = ConversationView.idFor(convId))
    for {
      _ <- TestSigil.withDB(_.views.transaction(_.upsert(view)))
      _ <- Task.sequence(msgs.toList.map(m => TestSigil.withDB(_.events.transaction(_.upsert(m)))))
    } yield msgs.map(_.timestamp.value)
  }

  /** Subscribe `TestUser` to signals, capture into a queue. */
  private def subscribe(): (ConcurrentLinkedQueue[Signal], () => Unit) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    TestSigil.signalsFor(TestUser)
      .evalMap(s => Task { recorded.add(s); () })
      .takeWhile(_ => running)
      .drain
      .startUnit()
    (recorded, () => running = false)
  }

  "ConversationSnapshot pagination" should {
    "bound recentEvents to the requested limit and set hasMore=true when older events exist" in {
      val convId = Conversation.id("paginated-snapshot")
      val (recorded, stop) = subscribe()
      for {
        _ <- seedConversation(convId, count = 250)
        _ <- TestSigil.handleNotice(SwitchConversation(convId, limit = 100), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        import scala.jdk.CollectionConverters.*
        val snap = recorded.iterator().asScala.toList.collectFirst {
          case s: ConversationSnapshot if s.conversationId == convId => s
        }.getOrElse(fail("Expected a ConversationSnapshot"))
        snap.recentEvents.size shouldBe 100
        snap.hasMore shouldBe true
        // Trailing window — the newest 100, in chronological order.
        snap.recentEvents.head.timestamp.value should be < snap.recentEvents.last.timestamp.value
      }
    }

    "set hasMore=false when the conversation fits in the window" in {
      val convId = Conversation.id("small-snapshot")
      val (recorded, stop) = subscribe()
      for {
        _ <- seedConversation(convId, count = 30)
        _ <- TestSigil.handleNotice(SwitchConversation(convId, limit = 100), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        import scala.jdk.CollectionConverters.*
        val snap = recorded.iterator().asScala.toList.collectFirst {
          case s: ConversationSnapshot if s.conversationId == convId => s
        }.getOrElse(fail("Expected a ConversationSnapshot"))
        snap.recentEvents.size shouldBe 30
        snap.hasMore shouldBe false
      }
    }
  }

  "RequestConversationHistory" should {
    "return the page of older events before the cursor with hasMore set correctly" in {
      val convId = Conversation.id("paginated-history")
      val (recorded, stop) = subscribe()
      for {
        timestamps <- seedConversation(convId, count = 250)
        // Cursor at the oldest timestamp in the most-recent window of 100.
        cursorMs = timestamps(150)
        _ <- TestSigil.handleNotice(RequestConversationHistory(convId, beforeMs = cursorMs, limit = 100), TestUser)
        _ <- Task.sleep(150.millis)
        // Second page — cursor at the oldest of the previous page.
      } yield {
        stop()
        import scala.jdk.CollectionConverters.*
        val pages = recorded.iterator().asScala.toList.collect {
          case h: ConversationHistorySnapshot if h.conversationId == convId => h
        }
        pages should have size 1
        val first = pages.head
        first.events.size shouldBe 100
        first.hasMore shouldBe true
        first.events.last.timestamp.value should be < cursorMs
      }
    }

    "return remaining events with hasMore=false when the page reaches the start" in {
      val convId = Conversation.id("paginated-history-end")
      val (recorded, stop) = subscribe()
      for {
        timestamps <- seedConversation(convId, count = 250)
        // Cursor at the 50th-oldest event — only 50 older events remain.
        cursorMs = timestamps(50)
        _ <- TestSigil.handleNotice(RequestConversationHistory(convId, beforeMs = cursorMs, limit = 100), TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        stop()
        import scala.jdk.CollectionConverters.*
        val page = recorded.iterator().asScala.toList.collectFirst {
          case h: ConversationHistorySnapshot if h.conversationId == convId => h
        }.getOrElse(fail("Expected a ConversationHistorySnapshot"))
        page.events.size shouldBe 50
        page.hasMore shouldBe false
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
