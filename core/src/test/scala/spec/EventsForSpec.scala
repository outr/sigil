package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, Topic, TopicEntry}
import sigil.event.{Event, Message, ToolInvoke}
import sigil.signal.EventState
import sigil.tool.ToolName
import sigil.tool.model.ResponseContent

/**
 * Coverage for [[sigil.Sigil.eventsFor]] — the canonical paged
 * conversation-history read.
 *
 * Each test seeds [[sigil.db.SigilDB.events]] with a controlled
 * timestamp fixture and asserts the message-counted paging, the
 * filter composition, the newest-first page ordering, and the
 * page-0 merge of still-uncommitted batched-iteration events.
 */
class EventsForSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConv(suffix: String): Id[Conversation] =
    Conversation.id(s"eventsfor-$suffix-${rapid.Unique()}")

  private def msg(convId: Id[Conversation],
                  ts: Long,
                  text: String,
                  topicId: Id[Topic] = TestTopicId): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = topicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = Timestamp(ts)
    )

  private def tool(convId: Id[Conversation],
                   ts: Long,
                   name: String,
                   topicId: Id[Topic] = TestTopicId): ToolInvoke =
    ToolInvoke(
      toolName = ToolName.parse(name).fold(sys.error, identity),
      participantId = TestUser,
      conversationId = convId,
      topicId = topicId,
      input = None,
      state = EventState.Complete,
      timestamp = Timestamp(ts)
    )

  /**
   * Text payload of a Message; empty for non-Message events.
   */
  private def label(e: Event): String = e match {
    case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString
    case t: ToolInvoke => t.toolName.value
    case _ => ""
  }

  "eventsFor message-counted paging" should {

    // Schedule: m, t, t, m, t, m, t, t, m, t, m, t, m, t, t, m, t, t, m, t
    // 8 Messages (m1..m8) interleaved with 12 ToolInvokes (t1..t12).
    val schedule = List(
      ("m", 100L, "m1"),
      ("t", 110L, "t1"),
      ("t", 120L, "t2"),
      ("m", 130L, "m2"),
      ("t", 140L, "t3"),
      ("m", 150L, "m3"),
      ("t", 160L, "t4"),
      ("t", 170L, "t5"),
      ("m", 180L, "m4"),
      ("t", 190L, "t6"),
      ("m", 200L, "m5"),
      ("t", 210L, "t7"),
      ("m", 220L, "m6"),
      ("t", 230L, "t8"),
      ("t", 240L, "t9"),
      ("m", 250L, "m7"),
      ("t", 260L, "t10"),
      ("t", 270L, "t11"),
      ("m", 280L, "m8"),
      ("t", 290L, "t12")
    )

    def seed(convId: Id[Conversation]): Task[Unit] =
      Task.sequence(schedule.map {
        case ("m", ts, l) => TestSigil.publish(msg(convId, ts, l))
        case (_, ts, l) => TestSigil.publish(tool(convId, ts, l))
      }).unit

    "return page 0 as the most recent maxMessages messages plus their trailing live edge" in {
      val convId = freshConv("page0")
      for {
        _ <- seed(convId)
        page <- TestSigil.eventsFor(convId, page = 0, maxMessages = Some(5))
      } yield {
        // 5th-newest Message is m4 (ts=180). Page 0 = m4 forward, including
        // every non-Message event newer than m4 (the live edge: t6..t12).
        page.events.map(label) shouldBe List(
          "m4",
          "t6",
          "m5",
          "t7",
          "m6",
          "t8",
          "t9",
          "m7",
          "t10",
          "t11",
          "m8",
          "t12"
        )
        page.events.collect { case m: Message => label(m) } shouldBe List("m4", "m5", "m6", "m7", "m8")
        page.hasMore shouldBe true
      }
    }

    "return page 1 strictly between its first and last Message, no live edge" in {
      val convId = freshConv("page1")
      for {
        _ <- seed(convId)
        page <- TestSigil.eventsFor(convId, page = 1, maxMessages = Some(5))
      } yield {
        // Page 1 messages are m3 down to... only m1,m2,m3 remain (3 older
        // than page 0's m4). First Message of page 1 = m3, last = m1.
        // Strictly the events from m3 back to m1 inclusive — t3 sits
        // between m2 and m3, t1/t2 between m1 and m2.
        page.events.map(label) shouldBe List("m1", "t1", "t2", "m2", "t3", "m3")
        page.events.collect { case m: Message => label(m) } shouldBe List("m1", "m2", "m3")
        page.hasMore shouldBe false
      }
    }

    "treat non-Message events as free — they never consume message budget" in {
      val convId = freshConv("budget")
      for {
        _ <- seed(convId)
        page <- TestSigil.eventsFor(convId, page = 0, maxMessages = Some(2))
      } yield {
        // maxMessages=2: page 0 holds m7,m8 — the two newest Messages —
        // plus every non-Message event from the live edge through m7:
        // t10/t11 interleave m7 and m8, t12 is the trailing live edge.
        // Three tool invokes ride along without touching the budget.
        page.events.map(label) shouldBe List("m7", "t10", "t11", "m8", "t12")
        page.events.collect { case m: Message => label(m) } shouldBe List("m7", "m8")
        page.events.count(_.isInstanceOf[ToolInvoke]) shouldBe 3
        page.hasMore shouldBe true
      }
    }

    "return every event on page 0 when maxMessages is None" in {
      val convId = freshConv("uncapped")
      for {
        _ <- seed(convId)
        page <- TestSigil.eventsFor(convId, page = 0, maxMessages = None)
      } yield {
        page.events.size shouldBe 20
        page.events.map(_.timestamp.value) shouldBe schedule.map(_._2)
        page.hasMore shouldBe false
      }
    }

    "return an empty page past the last message" in {
      val convId = freshConv("past-end")
      for {
        _ <- seed(convId)
        page <- TestSigil.eventsFor(convId, page = 5, maxMessages = Some(5))
      } yield {
        page.events shouldBe Nil
        page.hasMore shouldBe false
      }
    }
  }

  "eventsFor filters" should {

    "restrict to a single topic with topicId" in {
      val convId = freshConv("topic")
      val topicA = Id[Topic]("eventsfor-topic-a")
      val topicB = Id[Topic]("eventsfor-topic-b")
      for {
        _ <- TestSigil.publish(msg(convId, 100L, "a1", topicA))
        _ <- TestSigil.publish(msg(convId, 200L, "b1", topicB))
        _ <- TestSigil.publish(msg(convId, 300L, "a2", topicA))
        _ <- TestSigil.publish(msg(convId, 400L, "b2", topicB))
        page <- TestSigil.eventsFor(convId, maxMessages = None, topicId = Some(topicA))
      } yield page.events.map(label) shouldBe List("a1", "a2")
    }

    "bound the window with exclusive minTimestamp and maxTimestamp" in {
      val convId = freshConv("window")
      for {
        _ <- TestSigil.publish(msg(convId, 100L, "e1"))
        _ <- TestSigil.publish(msg(convId, 200L, "e2"))
        _ <- TestSigil.publish(msg(convId, 300L, "e3"))
        _ <- TestSigil.publish(msg(convId, 400L, "e4"))
        afterT <- TestSigil.eventsFor(convId, maxMessages = None, minTimestamp = Some(Timestamp(200L)))
        beforeT <- TestSigil.eventsFor(convId, maxMessages = None, maxTimestamp = Some(Timestamp(300L)))
        window <- TestSigil.eventsFor(
          convId,
          maxMessages = None,
          minTimestamp = Some(Timestamp(100L)),
          maxTimestamp = Some(Timestamp(400L))
        )
      } yield {
        // Both bounds are exclusive — the event at the bound is excluded.
        afterT.events.map(label) shouldBe List("e3", "e4")
        beforeT.events.map(label) shouldBe List("e1", "e2")
        window.events.map(label) shouldBe List("e2", "e3")
      }
    }

    "compose topicId, the timestamp window, and the message cap together" in {
      val convId = freshConv("compose")
      val topicA = Id[Topic]("eventsfor-compose-a")
      val topicB = Id[Topic]("eventsfor-compose-b")
      for {
        _ <- TestSigil.publish(msg(convId, 100L, "a1", topicA))
        _ <- TestSigil.publish(msg(convId, 150L, "b1", topicB))
        _ <- TestSigil.publish(msg(convId, 200L, "a2", topicA))
        _ <- TestSigil.publish(msg(convId, 300L, "a3", topicA))
        _ <- TestSigil.publish(msg(convId, 400L, "a4", topicA))
        page <- TestSigil.eventsFor(
          convId,
          page = 0,
          maxMessages = Some(2),
          topicId = Some(topicA),
          minTimestamp = Some(Timestamp(100L)),
          maxTimestamp = Some(Timestamp(400L))
        )
      } yield {
        // Window (100,400) exclusive on topicA = a2,a3. Cap of 2 fits both.
        page.events.map(label) shouldBe List("a2", "a3")
        page.hasMore shouldBe false
      }
    }
  }

  "eventsFor ordering and hasMore" should {

    "order events oldest-first within a page while paging newest-first" in {
      val convId = freshConv("ordering")
      for {
        _ <- TestSigil.publish(msg(convId, 100L, "x1"))
        _ <- TestSigil.publish(msg(convId, 200L, "x2"))
        _ <- TestSigil.publish(msg(convId, 300L, "x3"))
        _ <- TestSigil.publish(msg(convId, 400L, "x4"))
        page0 <- TestSigil.eventsFor(convId, page = 0, maxMessages = Some(2))
        page1 <- TestSigil.eventsFor(convId, page = 1, maxMessages = Some(2))
      } yield {
        // Page 0 = the two newest; page 1 = the two before. Within each
        // page events stay oldest-first.
        page0.events.map(label) shouldBe List("x3", "x4")
        page1.events.map(label) shouldBe List("x1", "x2")
        page0.hasMore shouldBe true
        page1.hasMore shouldBe false
      }
    }
  }

  "eventsFor page-0 in-flight merge" should {

    "merge still-uncommitted batched-iteration events into page 0 and drop the scope on commit" in {
      val convId = freshConv("inflight")
      // Seed two committed messages outside any batch.
      val committed = List(msg(convId, 100L, "committed-1"), msg(convId, 200L, "committed-2"))
      // Two events written inside an open batch — broadcast already,
      // not yet committed to the DB.
      val pending = List(msg(convId, 300L, "pending-1"), tool(convId, 310L, "pending-tool"))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
          Conversation(topics = TestTopicStack, participants = Nil, _id = convId)
        )))
        _ <- Task.sequence(committed.map(TestSigil.publish))
        result <- TestSigil.withDB(_.withBatchedEvents(convId) {
          for {
            // Write the pending events into the open batched scope.
            _ <- Task.sequence(pending.map(p => TestSigil.withDB(_.apply(p))))
            // The scope's accumulator holds the iteration's
            // still-uncommitted events — the page-0 merge source.
            acc <- TestSigil.withDB(db =>
              Task.pure(db.batchedEventScope(convId).map(_.snapshot.map(label).toSet)))
            // While the scope is still open, page 0 surfaces the
            // committed history merged with the pending edge.
            page0 <- TestSigil.eventsFor(convId, page = 0, maxMessages = None)
          } yield (acc, page0)
        })
        // The scope has exited — the registry entry is gone.
        scopeGone <- TestSigil.withDB(db => Task.pure(db.batchedEventScope(convId).isEmpty))
        // After the scope commits the pending events are durable — a
        // fresh read returns them straight from the DB.
        afterCommit <- TestSigil.eventsFor(convId, page = 0, maxMessages = None)
      } yield {
        val (acc, page0) = result
        // The accumulator carried exactly the iteration's writes.
        acc shouldBe Some(Set("pending-1", "pending-tool"))
        // Page 0 inside the scope shows committed history + the
        // uncommitted live edge, oldest-first, deduped by id.
        page0.events.map(label) shouldBe List("committed-1", "committed-2", "pending-1", "pending-tool")
        // The scope and its accumulator drop when the iteration commits.
        scopeGone shouldBe true
        afterCommit.events.map(label) shouldBe List("committed-1", "committed-2", "pending-1", "pending-tool")
      }
    }

  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
