package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation

/**
 * Coverage for [[sigil.Sigil.getOrCreateConversation]] — the
 * lazy-create-on-first-contact helper that every chat-shaped Sigil
 * consumer otherwise hand-rolls. Verifies:
 *
 *   - on a fresh id, a new Conversation is created with the supplied
 *     defaults
 *   - on a repeat call with the same id, the existing Conversation
 *     is returned unchanged regardless of the new defaults passed
 *   - the Conversation is actually persisted (round-trip via
 *     `withDB(_.conversations.transaction(_.get(...)))`)
 */
class GetOrCreateConversationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def freshId(suffix: String): Id[Conversation] =
    Conversation.id(s"goc-$suffix-${rapid.Unique()}")

  "Sigil.getOrCreateConversation" should {

    "create a fresh Conversation when none exists" in {
      val convId = freshId("create")
      for {
        created <- TestSigil.getOrCreateConversation(
                     conversationId = convId,
                     createdBy = TestUser,
                     label = "Greeting label",
                     summary = "Greeting summary"
                   )
        loaded  <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        created._id shouldBe convId
        // Topic invariant — a fresh conversation always has at least one Topic.
        created.topics should not be empty
        loaded shouldBe defined
        loaded.get._id shouldBe convId
      }
    }

    "return the existing Conversation when one already exists, ignoring new defaults" in {
      val convId = freshId("existing")
      for {
        first  <- TestSigil.getOrCreateConversation(
                    conversationId = convId,
                    createdBy = TestUser,
                    label = "First label",
                    summary = "First summary"
                  )
        // Second call with different defaults — should NOT mutate
        // the existing record. The returned Conversation should be
        // the original, not a new one with the new label/summary.
        second <- TestSigil.getOrCreateConversation(
                    conversationId = convId,
                    createdBy = TestUser,
                    label = "Different label",
                    summary = "Different summary"
                  )
      } yield {
        first._id shouldBe convId
        second._id shouldBe convId
        // Same record both times — topics list is identical (not
        // appended, not reset).
        second.topics shouldBe first.topics
      }
    }

    "be safely idempotent across many invocations on the same id" in {
      val convId = freshId("idempotent")
      val calls = (1 to 5).toList.map { _ =>
        TestSigil.getOrCreateConversation(
          conversationId = convId,
          createdBy = TestUser
        )
      }
      for {
        all <- rapid.Task.sequence(calls)
      } yield {
        all.map(_._id).distinct shouldBe List(convId)
        all.map(_.topics).distinct.size shouldBe 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
