package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ContextFrame}
import sigil.event.{Event, Message}
import sigil.signal.{ConversationHistoryImported, EventState, Signal}
import sigil.tool.model.ResponseContent

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Coverage for sigil bug #21 — `Sigil.publishHistorical(events,
 * conversationId)` for bulk-import. Asserts persistence, view rebuild,
 * single-Notice emission, and that the per-event publish pipeline
 * (hub.emit per event, fanOut, settled effects) does NOT run.
 */
class PublishHistoricalSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConvId(suffix: String): Id[Conversation] =
    Conversation.id(s"publish-historical-$suffix-${rapid.Unique()}")

  private def msg(convId: Id[Conversation], body: String, ts: Long): Message = {
    val base = Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(body)),
      state = EventState.Complete
    )
    base.copy(timestamp = Timestamp(ts))
  }

  "Sigil.publishHistorical" should {
    "persist every event under the target conversation" in {
      val convId = freshConvId("persist")
      val events: Seq[Event] = (1 to 25).map(i => msg(convId, s"line-$i", 1_000_000L + i))
      for {
        _ <- TestSigil.publishHistorical(events, convId)
        rows <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        val mine = rows.filter(_.conversationId == convId)
        mine should have size events.size
        mine.collect { case m: Message => m.content.collect { case t: ResponseContent.Text => t.text }.mkString }
          .sorted shouldBe events.collect { case m: Message =>
          m.content.collect { case t: ResponseContent.Text => t.text }.mkString
        }.sorted
      }
    }

    "rebuild the view to contain frames for every imported event" in {
      val convId = freshConvId("view")
      val events: Seq[Event] = (1 to 8).map(i => msg(convId, s"frame-$i", 2_000_000L + i))
      for {
        _ <- TestSigil.publishHistorical(events, convId)
        frames <- TestSigil.framesFor(convId)
      } yield {
        val texts = frames.collect { case t: ContextFrame.Text => t.content }
        texts shouldBe Vector("frame-1", "frame-2", "frame-3", "frame-4", "frame-5", "frame-6", "frame-7", "frame-8")
      }
    }

    "emit exactly one ConversationHistoryImported notice and zero per-event Events on the wire" in {
      val convId = freshConvId("wire")
      val events: Seq[Event] = (1 to 100).map(i => msg(convId, s"wire-$i", 3_000_000L + i))
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestSigil.signals
        .takeWhile(_ => running)
        .evalMap(s => Task { recorded.add(s); () })
        .drain
        .startUnit()

      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.publishHistorical(events, convId)
        _ <- Task.sleep(200.millis)
      } yield {
        running = false
        val all = recorded.iterator().asScala.toList
        val notices = all.collect { case n: ConversationHistoryImported if n.conversationId == convId => n }
        notices should have size 1
        notices.head.addedCount shouldBe 100

        val perEventMessages = all.collect {
          case e: Event if e.conversationId == convId => e
        }
        perEventMessages shouldBe empty
      }
    }

    "emit a ConversationHistoryImported(0) for an empty event list" in {
      val convId = freshConvId("empty")
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestSigil.signals
        .takeWhile(_ => running)
        .evalMap(s => Task { recorded.add(s); () })
        .drain
        .startUnit()

      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.publishHistorical(Nil, convId)
        _ <- Task.sleep(150.millis)
      } yield {
        running = false
        val notices = recorded.iterator().asScala.collect {
          case n: ConversationHistoryImported if n.conversationId == convId => n
        }.toList
        notices should have size 1
        notices.head.addedCount shouldBe 0
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
