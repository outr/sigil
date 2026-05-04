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
      toolName = ToolName(name),
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

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
