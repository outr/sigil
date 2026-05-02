package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ContextFrame}
import sigil.event.Message
import sigil.signal.{ConversationCleared, EventState, Signal}
import sigil.tool.model.ResponseContent

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Coverage for [[Sigil.clearConversation]] and the
 * [[ConversationCleared]] Notice. Pins the watermark semantics:
 *
 *   - The conversation row, participants, and current topic survive.
 *   - `ConversationView.frames` resets to empty after the clear.
 *   - Events published BEFORE the clear stay in `db.events` for
 *     audit but don't appear in the post-clear view.
 *   - Events published AFTER the clear flow through normally.
 *   - `rebuildView` honors the watermark — replay produces the same
 *     post-clear projection as the live path.
 *   - The Notice is broadcast so live viewers can reset their UI.
 */
class ConversationClearedSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshConvId(suffix: String): Id[Conversation] =
    Conversation.id(s"cleared-spec-$suffix-${rapid.Unique()}")

  private def msg(convId: Id[Conversation], text: String, ts: Long = System.currentTimeMillis()): Message =
    Message(
      participantId  = TestUser,
      conversationId = convId,
      topicId        = TestTopicId,
      content        = Vector(ResponseContent.Text(text)),
      state          = EventState.Complete,
      timestamp      = lightdb.time.Timestamp(ts)
    )

  "Sigil.clearConversation" should {

    "empty the ConversationView frames while keeping the conversation row alive" in {
      val convId = freshConvId("basic")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _      <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _      <- TestSigil.publish(msg(convId, "pre-clear-1"))
        _      <- TestSigil.publish(msg(convId, "pre-clear-2"))
        viewBefore <- TestSigil.viewFor(convId)
        _      <- TestSigil.clearConversation(convId, TestUser)
        viewAfter  <- TestSigil.viewFor(convId)
        convAfter  <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        viewBefore.frames should have size 2
        viewAfter.frames shouldBe empty
        // The conversation row is still alive — same id, topics intact.
        convAfter shouldBe defined
        convAfter.get._id shouldBe convId
        convAfter.get.topics shouldBe TestTopicStack
        convAfter.get.clearedAt shouldBe defined
      }
    }

    "leave events in db.events for audit (clear is soft, not a hard delete)" in {
      val convId = freshConvId("audit")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _      <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _      <- TestSigil.publish(msg(convId, "audit-pre-clear"))
        _      <- TestSigil.clearConversation(convId, TestUser)
        events <- TestSigil.withDB(_.events.transaction(_.list))
      } yield {
        // The pre-clear message is still in the events store.
        val ours = events.filter(_.conversationId == convId).toList
        ours.size should be >= 1
        ours.exists {
          case m: Message => m.content.exists {
            case ResponseContent.Text(t) => t == "audit-pre-clear"
            case _                       => false
          }
          case _ => false
        } shouldBe true
      }
    }

    "let new events appear in the view after the clear" in {
      val convId = freshConvId("post-clear")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _    <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _    <- TestSigil.publish(msg(convId, "pre-clear"))
        _    <- TestSigil.clearConversation(convId, TestUser)
        // Use a future-stamped message so it definitely lands after
        // the watermark even if wall-clock granularity collides.
        _    <- TestSigil.publish(msg(convId, "post-clear", System.currentTimeMillis() + 5000))
        view <- TestSigil.viewFor(convId)
      } yield {
        view.frames.collect { case t: ContextFrame.Text => t.content } shouldBe Vector("post-clear")
      }
    }

    "broadcast a ConversationCleared Notice carrying the watermark + clearedBy" in {
      val convId = freshConvId("notice")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val recorded = new ConcurrentLinkedQueue[Signal]()
      @volatile var running = true
      TestSigil.signals
        .takeWhile(_ => running)
        .evalMap(s => Task { recorded.add(s); () })
        .drain
        .startUnit()

      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.clearConversation(convId, TestUser)
        _ <- Task.sleep(150.millis)
      } yield {
        running = false
        import scala.jdk.CollectionConverters.*
        val notices = recorded.iterator().asScala.collect { case n: ConversationCleared => n }.toList
        val ours = notices.filter(_.conversationId == convId)
        ours should have size 1
        ours.head.clearedBy shouldBe TestUser
        ours.head.clearedAt.value should be > 0L
      }
    }

    "rebuildView reproduces the same empty post-clear projection" in {
      val convId = freshConvId("rebuild")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        _ <- TestSigil.publish(msg(convId, "pre-rebuild"))
        _ <- TestSigil.clearConversation(convId, TestUser)
        rebuilt <- TestSigil.rebuildView(convId)
      } yield {
        rebuilt.frames shouldBe empty
      }
    }

    "no-op silently when the conversation doesn't exist" in {
      val convId = freshConvId("missing")
      // Don't seed the conversation — clearConversation should be a
      // silent no-op rather than crashing.
      TestSigil.clearConversation(convId, TestUser).map(_ => succeed)
    }
  }
}
