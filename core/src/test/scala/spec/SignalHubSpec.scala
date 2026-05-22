package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, FiberOps, Task}
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.pipeline.SignalHub
import sigil.signal.{EventState, Signal, StateDelta}
import sigil.tool.model.ResponseContent

import scala.concurrent.duration.*

/**
 * Coverage for [[sigil.pipeline.SignalHub]] subscriber-registration
 * semantics.
 *
 * The original bug (BUGS.md Sigil#3): `subscribe` registered the
 * subscriber's queue lazily — only on the stream's first pull, via
 * `Stream.using`'s setup task. Callers that returned the stream to a
 * fiber via `startUnit()` (e.g. `SignalTransport.attach`) had a window
 * between the `subscribe` call and the fiber's first pull where any
 * concurrent `emit()` would route to no subscriber and be dropped.
 *
 * The fix: `subscribe` registers the queue synchronously when called.
 * The stream still pulls lazily, but emissions land in the queue from
 * the moment subscription returns.
 */
class SignalHubSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  private def msg(text: String, ts: Long = 0L): Signal =
    Message(
      participantId = TestUser,
      conversationId = Conversation.id("hub-test"),
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete,
      timestamp = Timestamp(ts)
    )

  /** A transient `Delta` signal — sheddable under overflow. */
  private def delta(targetId: String): Signal =
    StateDelta(
      target = Id[Event](targetId),
      conversationId = Conversation.id("hub-test"),
      state = EventState.Complete
    )

  "SignalHub.subscribe" should {
    "register the subscriber synchronously — subscriberCount reflects the new sub before the stream is consumed" in Task {
      val hub = new SignalHub
      val before = hub.subscriberCount
      val _ = hub.subscribe
      val after = hub.subscriberCount
      // Pre-fix: setup runs lazily on first pull, so `after` == `before`.
      // Post-fix: setup runs synchronously inside `subscribe`, so the
      // queue is registered the moment the stream value is built.
      after - before shouldBe 1
    }

    "deliver emissions that happen between subscribe and stream consumption" in {
      val hub = new SignalHub
      // Subscribe FIRST, build the stream value.
      val stream = hub.subscribe
      // Emit BEFORE anyone pulls — pre-fix, this signal is dropped (no
      // subscriber yet). Post-fix, the queue is hot and the signal
      // lands inside it for the consumer to pick up.
      hub.emit(msg("pre-pull-1"))
      hub.emit(msg("pre-pull-2"))
      // Now consume the stream until the close sentinel.
      val drainTask = stream.toList
      val draining = drainTask.start()
      // Trigger termination so toList completes.
      Task.sleep(50.millis).flatMap { _ =>
        Task { hub.close() }.flatMap { _ =>
          draining.flatMap { signals =>
            val texts = signals.collect {
              case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString
            }
            Task.pure(texts shouldBe List("pre-pull-1", "pre-pull-2"))
          }
        }
      }
    }

    "remove the subscriber on stream termination via close sentinel" in {
      val hub = new SignalHub
      val stream = hub.subscribe
      hub.subscriberCount shouldBe 1
      val drainTask = stream.toList.start()
      Task.sleep(25.millis).flatMap { _ =>
        Task { hub.close() }.flatMap { _ =>
          drainTask.flatMap { _ =>
            // After the stream completes, the subscriber should be
            // removed from the hub's list.
            Task.pure(hub.subscriberCount shouldBe 0)
          }
        }
      }
    }
  }

  "SignalHub overflow policy (bug #256)" should {

    "shed the oldest transient signal before a durable Event when a subscriber queue overflows" in {
      val hub = new SignalHub(subscriberCapacity = 4)
      val stream = hub.subscribe
      // Fill the capacity-4 queue: two Events and two transient Deltas.
      hub.emit(msg("E1"))
      hub.emit(delta("D1"))
      hub.emit(delta("D2"))
      hub.emit(msg("E2"))
      // Two more Events overflow the queue. Each makes room by shedding
      // the oldest transient — never an Event — so both Deltas go and
      // every Event stays queued.
      hub.emit(msg("E3"))
      hub.emit(msg("E4"))
      val draining = stream.toList.start()
      Task.sleep(50.millis).flatMap { _ =>
        Task { hub.close() }.flatMap { _ =>
          draining.flatMap { signals =>
            val texts  = signals.collect {
              case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString
            }
            val deltas = signals.collect { case d: StateDelta => d }
            Task.pure {
              // Both transients were shed; no Delta survived.
              deltas shouldBe empty
              // The Events survived the overflow (the close sentinel
              // evicts the single oldest, E1, to make room for itself).
              texts should contain allOf ("E2", "E3", "E4")
            }
          }
        }
      }
    }

    "drop the oldest Event only when the queue holds no transient to shed" in {
      val hub = new SignalHub(subscriberCapacity = 3)
      val stream = hub.subscribe
      hub.emit(msg("E1"))
      hub.emit(msg("E2"))
      hub.emit(msg("E3"))
      // Queue is full and holds only Events — this overflow has no
      // transient to shed, so the oldest Event (E1) is dropped.
      hub.emit(msg("E4"))
      val draining = stream.toList.start()
      Task.sleep(50.millis).flatMap { _ =>
        Task { hub.close() }.flatMap { _ =>
          draining.flatMap { signals =>
            val texts = signals.collect {
              case m: Message => m.content.collect { case ResponseContent.Text(t) => t }.mkString
            }
            Task.pure {
              texts should not contain "E1" // oldest Event dropped on overflow
              texts should contain ("E4")   // newest Event kept
            }
          }
        }
      }
    }
  }
}
