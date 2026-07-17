package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.ConversationNotFoundException
import sigil.conversation.{Conversation, ConversationStatus}
import sigil.signal.{ConversationStatusChanged, Signal}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Sigil #386 — app-defined, durable [[ConversationStatus]] on
 * [[Conversation]]: defaults to `Open`, set via `setConversationStatus`
 * (persist + `ConversationStatusChanged` notice), queryable server-side by
 * its payload-independent `key` index.
 */
class ConversationStatusSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def newConv(): Task[Conversation] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(
      Conversation(topics = TestTopicStack, _id = Conversation.id(s"cs-${rapid.Unique()}")))))

  private def reload(id: lightdb.id.Id[Conversation]): Task[Conversation] =
    TestSigil.withDB(_.conversations.transaction(_.get(id))).map(_.getOrElse(fail("conversation vanished")))

  "Conversation.status (sigil #386)" should {
    "default a new conversation to Open" in Task {
      val conv = Conversation(topics = TestTopicStack)
      conv.status shouldBe ConversationStatus.Open
      conv.status.key shouldBe "open"
    }
  }

  "setConversationStatus" should {

    "persist an app-defined status" in {
      for {
        conv <- newConv()
        updated <- TestSigil.setConversationStatus(conv._id, TestSavedStatus)
        rel <- reload(conv._id)
      } yield {
        updated.status shouldBe TestSavedStatus
        rel.status shouldBe TestSavedStatus
        rel.status.key shouldBe "test-saved"
      }
    }

    "round-trip a DATA-carrying status and preserve its category key" in {
      for {
        conv <- newConv()
        _ <- TestSigil.setConversationStatus(conv._id, TestCompletedStatus(at = 1234L))
        rel <- reload(conv._id)
      } yield {
        rel.status shouldBe TestCompletedStatus(1234L)
        rel.status.key shouldBe "test-completed"
      }
    }

    "be an idempotent no-op when the status is unchanged" in {
      for {
        conv <- newConv()
        _ <- TestSigil.setConversationStatus(conv._id, TestSavedStatus)
        again <- TestSigil.setConversationStatus(conv._id, TestSavedStatus)
      } yield again.status shouldBe TestSavedStatus
    }

    "raise ConversationNotFoundException for a missing conversation" in
      TestSigil.setConversationStatus(Conversation.id("cs-missing"), TestSavedStatus)
        .map(_ => fail("expected ConversationNotFoundException"))
        .handleError {
          case _: ConversationNotFoundException => Task.pure(succeed)
          case other => Task.error(other)
        }
  }

  "the statusKey index" should {
    "filter conversations by status category server-side, payload-independent" in {
      for {
        a <- newConv(); b <- newConv(); c <- newConv(); d <- newConv()
        _ <- TestSigil.setConversationStatus(a._id, TestCompletedStatus(at = 1L))
        _ <- TestSigil.setConversationStatus(b._id, TestCompletedStatus(at = 999L)) // different payload, same key
        _ <- TestSigil.setConversationStatus(c._id, TestSavedStatus)
        // d stays Open (default)
        completed <- TestSigil.withDB(_.conversations.transaction(
          _.query.filter(_.statusKey === "test-completed").toList))
        open <- TestSigil.withDB(_.conversations.transaction(
          _.query.filter(_.statusKey === "open").toList))
      } yield {
        // Both Completed conversations match despite different payloads.
        // (Membership, not exact set — the spec's DB is shared across tests.)
        completed.map(_._id) should contain allElementsOf List(a._id, b._id)
        // The other-category and default conversations do NOT match.
        completed.map(_._id) should not contain c._id
        completed.map(_._id) should not contain d._id
        // The untouched conversation is queryable under the default key.
        open.map(_._id) should contain(d._id)
      }
    }
  }

  "ConversationStatusChanged" should {
    "broadcast on a real change and stay silent on a redundant set" in {
      val recorded = new ConcurrentLinkedQueue[Signal]()
      val running = new AtomicBoolean(true)
      TestSigil.signals.takeWhile(_ => running.get())
        .evalMap(s => Task { recorded.add(s); () }).drain.startUnit()

      def statusNotices(convId: lightdb.id.Id[Conversation]): List[ConversationStatusChanged] =
        recorded.iterator().asScala.collect {
          case c: ConversationStatusChanged if c.conversationId == convId => c
        }.toList

      for {
        _ <- Task.sleep(100.millis) // let the subscription establish
        conv <- newConv()
        _ <- TestSigil.setConversationStatus(conv._id, TestSavedStatus)
        _ <- TestSigil.setConversationStatus(conv._id, TestSavedStatus) // redundant → no notice
        _ <- Task.sleep(200.millis)
      } yield {
        running.set(false)
        val notices = statusNotices(conv._id)
        notices.map(_.status) shouldBe List(TestSavedStatus) // exactly one, from the real change
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
