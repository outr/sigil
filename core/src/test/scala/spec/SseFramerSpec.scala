package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.event.{Event, Message}
import sigil.signal.{ContentDelta, ContentKind, EventState, MessageDelta, Signal}
import sigil.tool.model.ResponseContent
import sigil.transport.SseFramer

import java.util.concurrent.atomic.AtomicReference

/**
 * Coverage for SSE framing — `id:` header behavior, `data:` payload,
 * Delta-vs-Event distinction, and the callback-style sink.
 */
class SseFramerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId: Id[Conversation] = Conversation.id("sse-framer-conv")

  private val event: Message = Message(
    participantId = TestUser,
    conversationId = convId,
    topicId = TestTopicId,
    content = Vector(ResponseContent.Text("hello")),
    state = EventState.Complete,
    timestamp = Timestamp(1700000000000L)
  )

  private val delta: MessageDelta = MessageDelta(
    target = event._id,
    conversationId = convId,
    content = Some(ContentDelta(kind = ContentKind.Text, arg = None, complete = false, delta = "hi"))
  )

  "SseFramer.formatFrame" should {

    "emit `id: <epoch-millis>` followed by `data: <json>` for an Event" in {
      val frame = SseFramer.formatFrame(event)
      frame should startWith("id: 1700000000000\n")
      frame should include("data: ")
      frame should endWith("\n\n")
    }

    "omit the `id:` line for a Delta (no resume cursor)" in {
      val frame = SseFramer.formatFrame(delta)
      frame should not include "id: "
      frame should startWith("data: ")
      frame should endWith("\n\n")
    }

    "use a custom idFor when supplied via Config" in {
      val cfg = SseFramer.Config(idFor = _ => Some("custom-id"))
      SseFramer.formatFrame(event, cfg) should startWith("id: custom-id\n")
    }
  }

  "SseFramer.sink" should {

    "invoke the write callback with one SSE frame per push" in {
      val captured = new AtomicReference[Vector[String]](Vector.empty)
      val sink = SseFramer.sink(s => Task { captured.updateAndGet(_ :+ s); () })
      for {
        _ <- sink.push(event)
        _ <- sink.push(delta)
      } yield {
        val frames = captured.get()
        frames should have size 2
        frames.head should startWith("id: 1700000000000\n")
        frames.last should startWith("data: ")
      }
    }

    "be a no-op on close (apps own the underlying response lifecycle)" in {
      val captured = new AtomicReference[Vector[String]](Vector.empty)
      val sink = SseFramer.sink(s => Task { captured.updateAndGet(_ :+ s); () })
      for {
        _ <- sink.close
        _ <- sink.push(event) // still works after close — close on the framer is a no-op
      } yield captured.get() should have size 1
    }
  }

  "SseFramer.Heartbeat" should {
    "be a valid SSE comment frame" in {
      Task(SseFramer.Heartbeat shouldBe ":hb\n\n")
    }
  }
}
