package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.event.{Event, Message, ToolInvoke}
import sigil.signal.{ConversationCreated, EventState, Signal}
import sigil.spatial.Place
import lightdb.spatial.Point
import sigil.tool.{ToolName, model}
import sigil.tool.model.ResponseContent
import sigil.transport.{ResumeRequest, SignalSink, SignalTransport, SinkHandle}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * Coverage for the database-driven replay path on [[SignalTransport]].
 * Each test seeds [[sigil.db.SigilDB.events]] with a known fixture
 * (no live LLM, no SignalHub races) and asserts the replay stream
 * reflects what the [[ResumeRequest]] semantics promise.
 */
class SignalTransportSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Threadsafe recording sink used by the live-attach tests. */
  private final class RecordingSink extends SignalSink {
    private val seen = new AtomicReference[Vector[Signal]](Vector.empty)
    private val closed = new AtomicReference[Boolean](false)
    override def push(signal: Signal): Task[Unit] = Task {
      seen.updateAndGet(_ :+ signal)
      ()
    }
    override def close: Task[Unit] = Task { closed.set(true) }
    def signals: Vector[Signal] = seen.get()
    def isClosed: Boolean = closed.get()
  }

  private val transport = new SignalTransport(TestSigil)

  /** Convenience: build a Message with a controlled `timestamp`. */
  private def msg(convId: Id[Conversation], ts: Long, text: String): Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = Timestamp(ts)
    )

  /** Convenience: build a ToolInvoke with a controlled `timestamp`. */
  private def tool(convId: Id[Conversation], ts: Long, name: String): ToolInvoke =
    ToolInvoke(
      toolName = ToolName.parse(name).fold(sys.error, identity),
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicId,
      input = None,
      state = EventState.Complete,
      timestamp = Timestamp(ts)
    )

  private def freshConv(suffix: String): Id[Conversation] =
    Conversation.id(s"transport-$suffix-${rapid.Unique()}")

  "SignalTransport.replay" should {

    "return an empty stream for ResumeRequest.None" in {
      val convId = freshConv("none")
      for {
        _ <- TestSigil.publish(msg(convId, 1000L, "hello"))
        signals <- transport.replay(TestUser, ResumeRequest.None,
                                    Some(Set(convId))).toList
      } yield signals shouldBe Vector.empty
    }

    "return only events with timestamp > cursor for ResumeRequest.After" in {
      val convId = freshConv("after")
      for {
        _ <- TestSigil.publish(msg(convId, 100L, "old"))
        _ <- TestSigil.publish(msg(convId, 200L, "boundary"))
        _ <- TestSigil.publish(msg(convId, 300L, "new1"))
        _ <- TestSigil.publish(msg(convId, 400L, "new2"))
        signals <- transport.replay(TestUser, ResumeRequest.After(200L),
                                    Some(Set(convId))).toList
      } yield {
        val texts = signals.collect {
          case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString
        }
        texts shouldBe Vector("new1", "new2")
      }
    }

    "return the most recent N Messages plus interleaved non-Message events for RecentMessages" in {
      val convId = freshConv("recent")
      // Seed 8 Messages interleaved with 12 ToolInvokes — total 20 events.
      // Interleave timestamps so the order is `m, t, t, m, t, m, t, t, m, ...`
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
      for {
        _ <- Task.sequence(schedule.map {
          case ("m", ts, label) => TestSigil.publish(msg(convId, ts, label))
          case (_, ts, label)   => TestSigil.publish(tool(convId, ts, label))
        })
        signals <- transport.replay(TestUser, ResumeRequest.RecentMessages(5),
                                    Some(Set(convId))).toList
      } yield {
        val messages = signals.collect { case m: Message =>
          m.content.collect { case ResponseContent.Text(t) => t }.mkString
        }
        // Cutoff is the 5th-newest Message — `m4` at ts=180. Everything
        // from (and including) m4 forward should appear; m1/m2/m3 should not.
        messages shouldBe Vector("m4", "m5", "m6", "m7", "m8")
        // The 6 ToolInvokes after m4 (t6..t12, less t6 which is at 190) — actually
        // every ToolInvoke with ts >= 180 (t6..t12) should be present.
        val tools = signals.collect { case t: ToolInvoke => t.toolName.value }
        tools shouldBe Vector("t6", "t7", "t8", "t9", "t10", "t11", "t12")
        // Total surfaced = 5 messages + 7 tool invokes = 12.
        signals.size shouldBe 12
      }
    }

    "produce nothing for RecentMessages(0)" in {
      val convId = freshConv("zero")
      for {
        _ <- TestSigil.publish(msg(convId, 100L, "anything"))
        signals <- transport.replay(TestUser, ResumeRequest.RecentMessages(0),
                                    Some(Set(convId))).toList
      } yield signals shouldBe Vector.empty
    }

    "apply viewerTransforms to replayed events (RedactLocationTransform strips Message.location)" in {
      val convId = freshConv("redact")
      val placed = Message(
        participantId = TestAgent, // sender != viewer
        conversationId = convId,
        topicId = TestTopicId,
        content = Vector(ResponseContent.Text("with-location")),
        state = EventState.Complete,
        timestamp = Timestamp(500L),
        location = Some(Place(Point(40.0, -74.0), name = Some("Origin"), address = None))
      )
      for {
        _ <- TestSigil.publish(placed)
        // Viewer is TestUser, who is NOT the sender — RedactLocationTransform
        // should strip location on replay.
        signals <- transport.replay(TestUser, ResumeRequest.After(0L),
                                    Some(Set(convId))).toList
      } yield {
        val redactedMsg = signals.collectFirst { case m: Message => m }
        redactedMsg shouldBe defined
        redactedMsg.get.location shouldBe None
      }
    }
  }

  "SignalTransport.attach" should {

    // Regression for BUGS.md Sigil#3 — `attach` must register the live
    // SignalHub subscription synchronously before returning, so any
    // signal published immediately after the call lands in the
    // subscriber's queue (rather than being dropped by the hub for
    // having no matching subscriber).
    //
    // Without the fix, this test fails: every signal published in the
    // tight loop below races against the consumer fiber's first pull
    // (which is what registers the queue with the hub under the old
    // lazy subscribe). With the fix (eager registration in
    // `SignalHub.subscribe`), every signal queues the moment it's
    // emitted and the fiber drains them deterministically.
    "register the live subscription synchronously — no signal loss for publishes that race attach()" in {
      val convId = freshConv("race")
      val sink = new RecordingSink
      val publishCount = 50
      for {
        handle <- transport.attach(TestUser, sink, ResumeRequest.None,
                                   conversations = Some(Set(convId)))
        // Synchronous publish loop with NO sleep between attach() and
        // the first publish — exercises the race window.
        _ <- Task.sequence((1 to publishCount).toList.map { i =>
               TestSigil.publish(msg(convId, 1000L + i, s"race-$i"))
             })
        // Drain window for the consumer fiber.
        _ <- Task.sleep(250.millis)
        _ <- handle.detach
      } yield {
        val texts = sink.signals.collect {
          case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString
        }
        // Every published signal must reach the sink — none lost to the race.
        texts should have size publishCount.toLong
        texts.toSet should be(((1 to publishCount).map(i => s"race-$i")).toSet)
      }
    }

    // Regression for BUGS.md Sigil#15 — `forwarded`'s filter handled
    // only Event and Delta. The first Notice arriving on the live stream
    // raised a MatchError that killed the drain fiber silently, so every
    // subsequent Event/Delta queued in the hub but was never pushed to
    // the sink.
    "keep draining after a Notice — Notice arms the filter, drain fiber survives" in {
      val convId = freshConv("notice")
      val sink = new RecordingSink
      for {
        handle <- transport.attach(TestUser, sink, ResumeRequest.None,
                                   conversations = Some(Set(convId)))
        // Notice first — pre-fix this would kill the drain fiber.
        _ <- TestSigil.publish(ConversationCreated(convId, TestUser))
        // Now publish ordinary Events; with the fix they must still flow.
        _ <- TestSigil.publish(msg(convId, 1000L, "after-notice-1"))
        _ <- TestSigil.publish(msg(convId, 2000L, "after-notice-2"))
        _ <- Task.sleep(200.millis)
        _ <- handle.detach
      } yield {
        sink.signals.exists(_.isInstanceOf[ConversationCreated]) shouldBe true
        val texts = sink.signals.collect {
          case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString
        }
        texts shouldBe Vector("after-notice-1", "after-notice-2")
      }
    }

    "forward replayed history first, then live signals after the boundary" in {
      val convId = freshConv("attach")
      val sink = new RecordingSink
      // Pre-seed two events so replay has something to deliver.
      for {
        _ <- TestSigil.publish(msg(convId, 1000L, "history-1"))
        _ <- TestSigil.publish(msg(convId, 2000L, "history-2"))
        handle <- transport.attach(TestUser, sink, ResumeRequest.After(0L),
                                   conversations = Some(Set(convId)))
        // Allow the replay fiber to drain the seeded events before publishing live.
        _ <- Task.sleep(150.millis)
        // Publish a fresh live signal — should arrive at the sink AFTER replay.
        _ <- TestSigil.publish(msg(convId, 3000L, "live-1"))
        _ <- Task.sleep(150.millis)
        _ <- handle.detach
      } yield {
        val texts = sink.signals.collect {
          case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString
        }
        // Each text should appear at most once — the boundary filter dedupes.
        texts.toSet should contain allOf ("history-1", "history-2", "live-1")
        texts.count(_ == "history-1") shouldBe 1
        texts.count(_ == "history-2") shouldBe 1
        texts.count(_ == "live-1") shouldBe 1
        sink.isClosed shouldBe true
      }
    }
  }

  "SignalTransport client-driven conversation scope (sigil #334)" should {

    /** Pull the texts out of a signal collection. */
    def textsOf(signals: Iterable[Signal]): List[String] =
      signals.collect {
        case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString
      }.toList

    "NOT auto-expand a parent subscription to its workers — replay scoped to the parent excludes worker events" in {
      val parentId = freshConv("parent")
      val workerId = freshConv("worker")
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(_id = parentId, topics = TestTopicStack))))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(_id = workerId, topics = TestTopicStack, parentConversationId = Some(parentId)))))
        _ <- TestSigil.publish(msg(parentId, 1000L, "parent-event"))
        _ <- TestSigil.publish(msg(workerId, 1100L, "worker-event"))
        signals <- transport.replay(TestUser, ResumeRequest.After(0L),
                                    conversations = Some(Set(parentId))).toList
      } yield {
        val texts = textsOf(signals)
        texts should contain ("parent-event")
        texts should not contain "worker-event" // the parent isn't subscribed to the worker
      }
    }

    "NOT transitively pull grandchild worker events into a grandparent subscription" in {
      val gpId = freshConv("grandparent")
      val pId  = freshConv("subparent")
      val cId  = freshConv("subchild")
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(_id = gpId, topics = TestTopicStack))))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(_id = pId, topics = TestTopicStack, parentConversationId = Some(gpId)))))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(
               Conversation(_id = cId, topics = TestTopicStack, parentConversationId = Some(pId)))))
        _ <- TestSigil.publish(msg(gpId, 1000L, "gp-event"))
        _ <- TestSigil.publish(msg(pId,  1100L, "p-event"))
        _ <- TestSigil.publish(msg(cId,  1200L, "c-event"))
        signals <- transport.replay(TestUser, ResumeRequest.After(0L),
                                    conversations = Some(Set(gpId))).toList
      } yield {
        val texts = textsOf(signals)
        texts should contain ("gp-event")
        texts should not contain "p-event"
        texts should not contain "c-event"
      }
    }

    "scope replay to exactly the declared set" in {
      val convA = freshConv("a")
      val convB = freshConv("b")
      for {
        _ <- TestSigil.publish(msg(convA, 1000L, "a-event"))
        _ <- TestSigil.publish(msg(convB, 1100L, "b-event"))
        signals <- transport.replay(TestUser, ResumeRequest.After(0L),
                                    conversations = Some(Set(convA))).toList
      } yield {
        val texts = textsOf(signals)
        texts should contain ("a-event")
        texts should not contain "b-event"
      }
    }

    "scope LIVE forwarding — a worker's live event never reaches a parent-only sink" in {
      val parentId = freshConv("live-parent")
      val workerId = freshConv("live-worker")
      val sink = new RecordingSink
      for {
        handle  <- transport.attach(TestUser, sink, ResumeRequest.None, conversations = Some(Set(parentId)))
        _       <- Task.sleep(100.millis)
        _       <- TestSigil.publish(msg(workerId, 5000L, "worker-live"))
        _       <- TestSigil.publish(msg(parentId, 5100L, "parent-live"))
        _       <- Task.sleep(150.millis)
        _       <- handle.detach
      } yield {
        val texts = textsOf(sink.signals)
        texts should contain ("parent-live")
        texts should not contain "worker-live"
      }
    }

    "deliver a conversation's live events after subscribe(convId), and stop after unsubscribe(convId)" in {
      val convA = freshConv("sub-a")
      val convB = freshConv("sub-b")
      val sink = new RecordingSink
      for {
        handle <- transport.attach(TestUser, sink, ResumeRequest.None, conversations = Some(Set(convA)))
        _      <- Task.sleep(100.millis)
        // B not yet subscribed — dropped.
        _      <- TestSigil.publish(msg(convB, 6000L, "b-before"))
        _      <- Task.sleep(100.millis)
        _      <- handle.subscribe(convB)
        _      <- TestSigil.publish(msg(convB, 6100L, "b-after-subscribe"))
        _      <- Task.sleep(100.millis)
        _      <- handle.unsubscribe(convB)
        _      <- TestSigil.publish(msg(convB, 6200L, "b-after-unsubscribe"))
        _      <- TestSigil.publish(msg(convA, 6300L, "a-always"))
        _      <- Task.sleep(150.millis)
        _      <- handle.detach
      } yield {
        val texts = textsOf(sink.signals)
        texts should not contain "b-before"
        texts should contain ("b-after-subscribe")
        texts should not contain "b-after-unsubscribe"
        texts should contain ("a-always")
      }
    }

    "scope conversation-bound Notices too — a ConversationNotice for an unsubscribed conversation is dropped" in {
      val convA = freshConv("notice-a")
      val convB = freshConv("notice-b")
      val sink = new RecordingSink
      for {
        handle <- transport.attach(TestUser, sink, ResumeRequest.None, conversations = Some(Set(convA)))
        _      <- Task.sleep(100.millis)
        _      <- TestSigil.publish(ConversationCreated(convA, TestUser))
        _      <- TestSigil.publish(ConversationCreated(convB, TestUser))
        _      <- Task.sleep(150.millis)
        _      <- handle.detach
      } yield {
        val created = sink.signals.collect { case c: ConversationCreated => c.conversationId }
        created should contain (convA)
        created should not contain convB
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
