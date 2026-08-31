package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.event.Message
import sigil.signal.{
  ConversationCleared, ConversationDeleted, EventState, RequestConversationClear,
  RequestConversationDelete, Signal
}
import sigil.tool.model.ResponseContent

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*

/**
 * Sigil #300 — verifies that the client→server lifecycle Notices
 * [[RequestConversationClear]] / [[RequestConversationDelete]] flow
 * through [[sigil.Sigil.handleNotice]] and trigger the corresponding
 * server-side action, which then broadcasts the authoritative
 * [[ConversationCleared]] / [[ConversationDeleted]] Notice to every
 * viewer.
 *
 * Pre-fix, the Tome client pushed an outbound-shaped
 * [[ConversationCleared]] which had no `handleNotice` case — the
 * action silently no-op'd. The Request* shape closes that gap by
 * naming the two directions distinctly.
 */
class RequestConversationLifecycleNoticeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def subscribe(viewer: sigil.participant.ParticipantId): (ConcurrentLinkedQueue[Signal], () => Unit) = {
    val recorded = new ConcurrentLinkedQueue[Signal]()
    @volatile var running = true
    TestSigil.signalsFor(viewer)
      .evalMap(s => Task { recorded.add(s); () })
      .takeWhile(_ => running)
      .drain
      .startUnit()
    (recorded, () => running = false)
  }

  import scala.jdk.CollectionConverters.*

  "RequestConversationClear (sigil #300)" should {

    "advance the conversation's clearedAt watermark and broadcast ConversationCleared" in {
      val convId = Conversation.id(s"req-clear-${rapid.Unique()}")
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.newConversation(createdBy = TestUser, conversationId = convId)
        _ <- TestSigil.publish(Message(
          participantId = TestUser,
          conversationId = convId,
          topicId = TestTopicEntry.id,
          content = Vector(ResponseContent.Text("first")),
          state = EventState.Complete
        ))
        before <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
        _ = before.flatMap(_.clearedAt) shouldBe None
        _ <- TestSigil.handleNotice(RequestConversationClear(convId), TestUser)
        _ <- Task.sleep(250.millis)
        after <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        stop()
        // Server-side action ran — clearedAt advanced.
        after.flatMap(_.clearedAt) should be(defined)
        // Broadcast Notice landed on the requester's stream, identifying
        // the requesting viewer as the clearedBy.
        val cleared = recorded.iterator().asScala.toList.collectFirst {
          case c: ConversationCleared if c.conversationId == convId => c
        }
        cleared.map(_.clearedBy) shouldBe Some(TestUser)
      }
    }
  }

  "RequestConversationDelete (sigil #300)" should {

    "purge the conversation row and broadcast ConversationDeleted" in {
      val convId = Conversation.id(s"req-delete-${rapid.Unique()}")
      val (recorded, stop) = subscribe(TestUser)
      for {
        _ <- Task.sleep(100.millis)
        _ <- TestSigil.newConversation(createdBy = TestUser, conversationId = convId)
        before <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
        _ = before should be(defined)
        _ <- TestSigil.handleNotice(RequestConversationDelete(convId), TestUser)
        _ <- Task.sleep(250.millis)
        after <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        stop()
        // Conversation row gone.
        after shouldBe None
        // Broadcast Notice landed on the requester's stream.
        val deleted = recorded.iterator().asScala.toList.collectFirst {
          case d: ConversationDeleted if d.conversationId == convId => d
        }
        deleted should be(defined)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
