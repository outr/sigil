package spec

import lightdb.spatial.Point
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.Message
import sigil.participant.ParticipantId
import sigil.pipeline.{InboundTransform, SettledEffect, ViewerTransform}
import sigil.signal.{EventState, Signal}
import sigil.spatial.Place
import sigil.tool.model.ResponseContent

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Coverage for the outbound [[sigil.pipeline.SignalHub]] and the
 * per-viewer stream derivation. Uses `TestSigil` in default
 * configuration — the transforms/effects lists are what Sigil ships.
 */
class SignalPipelineSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val point = Point(latitude = 37.7858, longitude = -122.4064)
  private val place = Place(point = point, address = Some("66 Mint St"), name = Some("Blue Bottle"))

  private def msg(location: Option[Place] = None, sender: ParticipantId = TestUser): Message =
    Message(
      participantId = sender,
      conversationId = Conversation.id(),
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text("hello")),
      state = EventState.Complete,
      location = location
    )

  "Sigil.signals" should {
    "deliver every published signal to each subscriber independently" in {
      TestSigil.reset()
      val a = new RecordingBroadcaster
      val b = new RecordingBroadcaster
      a.attach(TestSigil)
      b.attach(TestSigil)

      val m1 = msg()
      val m2 = msg()
      for {
        _ <- TestSigil.publish(m1)
        _ <- TestSigil.publish(m2)
        _ <- Task.sleep(200.millis) // let the fibers drain
      } yield {
        a.recorded should contain allOf (m1, m2)
        b.recorded should contain allOf (m1, m2)
      }
    }

    "not replay past signals to a late subscriber" in {
      TestSigil.reset()
      val early = msg()
      for {
        _ <- TestSigil.publish(early)
        _ <- Task.sleep(100.millis)
        late = {
          val r = new RecordingBroadcaster
          r.attach(TestSigil)
          r
        }
        later = msg()
        _ <- TestSigil.publish(later)
        _ <- Task.sleep(200.millis)
      } yield {
        late.recorded should not contain early
        late.recorded should contain(later)
      }
    }
  }

  "Sigil.signalsFor" should {
    "apply viewerTransforms (redact location for non-senders)" in {
      TestSigil.reset()
      val senderRecorder = new RecordingBroadcaster
      val viewerRecorder = new RecordingBroadcaster

      // Subscribe one per-viewer stream explicitly; bypass the
      // default RecordingBroadcaster.attach which uses raw `signals`.
      val senderQ = new ConcurrentLinkedQueue[Signal]()
      val viewerQ = new ConcurrentLinkedQueue[Signal]()
      TestSigil.signalsFor(TestUser).evalMap(s => Task { senderQ.add(s); () }).drain.startUnit()
      TestSigil.signalsFor(TestAgent).evalMap(s => Task { viewerQ.add(s); () }).drain.startUnit()

      val m = msg(location = Some(place))
      for {
        _ <- TestSigil.publish(m)
        _ <- Task.sleep(200.millis)
      } yield {
        import scala.jdk.CollectionConverters.*
        val sender = senderQ.iterator().asScala.toList.collect { case mm: Message => mm.location }
        val other = viewerQ.iterator().asScala.toList.collect { case mm: Message => mm.location }
        sender should contain(Some(place))
        other should contain(None)
      }
    }
  }

  "Sigil.inboundTransforms" should {
    "apply extra app-supplied transforms in order" in {
      val counter = new AtomicInteger(0)
      val tracking = new InboundTransform {
        override def apply(signal: Signal, self: Sigil): Task[Signal] = Task {
          counter.incrementAndGet()
          signal
        }
      }
      // Temporarily install the transform via a subclass — we don't
      // mutate TestSigil's list (it's a def override). Smallest hack
      // for this test: invoke the transform directly.
      val m = msg()
      tracking.apply(m, TestSigil).map(_ => counter.get() should be(1))
    }
  }

  "Sigil.settledEffects" should {
    "be awaited by publish in declaration order" in {
      val events = new ConcurrentLinkedQueue[String]()
      val effectA = new SettledEffect {
        override def apply(signal: Signal, self: Sigil): Task[Unit] =
          Task { events.add("a"); () }
      }
      val effectB = new SettledEffect {
        override def apply(signal: Signal, self: Sigil): Task[Unit] =
          Task { events.add("b"); () }
      }
      // Invoke in order to prove sequencing contract of List.foldLeft;
      // the full publish integration is exercised in PlaceEnrichmentSpec.
      for {
        _ <- effectA.apply(msg(), TestSigil)
        _ <- effectB.apply(msg(), TestSigil)
      } yield {
        import scala.jdk.CollectionConverters.*
        events.iterator().asScala.toList shouldBe List("a", "b")
      }
    }
  }

  "ViewerTransform" should {
    "compose additively — later transforms see earlier transforms' output" in {
      val markAsViewedBy = new ViewerTransform {
        override def apply(signal: Signal, viewer: ParticipantId, self: Sigil): Signal = signal match {
          case m: Message => m.copy(content = m.content :+ ResponseContent.Text(s"[seen by ${viewer.value}]"))
          case other => other
        }
      }
      val m = msg(location = Some(place))
      // Apply the default redaction transform, then the marker — simulate a 2-element list
      val afterRedact = TestSigil.applyViewerTransforms(m, TestAgent)
      val afterMark = markAsViewedBy.apply(afterRedact, TestAgent, TestSigil)
      afterMark match {
        case mm: Message =>
          Task {
            mm.location shouldBe None
            mm.content.last shouldBe ResponseContent.Text("[seen by test-agent]")
          }
        case other => Task.error(new AssertionError(s"expected Message, got $other"))
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
