package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, TopicEntry}

/**
 * Round-trip coverage for [[Conversation.parentConversationId]] and
 * [[Conversation.archived]]. These two fields drive the worker-
 * delegation hierarchy: a worker scratchpad conversation carries
 * `parentConversationId = Some(userFacingConv)`, and when its
 * owning workflow run settles the framework flips `archived = true`
 * so default listings hide the scratchpad while the audit row stays
 * queryable.
 *
 * In-memory filtering rather than indexed field queries — apps that
 * need indexed lookups (large fleets, frequent listings) layer their
 * own field declarations on the conversations store. Round-trip is
 * the framework guarantee.
 */
class ConversationHierarchySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def fresh(parentId: Option[lightdb.id.Id[Conversation]] = None,
                    archived: Boolean = false): Conversation =
    Conversation(
      topics = List(TopicEntry(TestTopicId, "test", "test")),
      parentConversationId = parentId,
      archived = archived
    )

  "Conversation.parentConversationId" should {
    "default to None for a top-level conversation" in {
      val conv = fresh()
      conv.parentConversationId shouldBe None
      rapid.Task.pure(succeed)
    }

    "round-trip through the conversations store when set" in {
      val parent = fresh()
      val child = fresh(parentId = Some(parent._id))
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(parent)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(child)))
        loaded <- TestSigil.withDB(_.conversations.transaction(_.get(child._id)))
      } yield loaded.flatMap(_.parentConversationId) shouldBe Some(parent._id)
    }

    "let apps locate a conversation's children by scanning the store" in {
      val parent = fresh()
      val childA = fresh(parentId = Some(parent._id))
      val childB = fresh(parentId = Some(parent._id))
      val unrelated = fresh()
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(parent)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(childA)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(childB)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(unrelated)))
        all <- TestSigil.withDB(_.conversations.transaction(_.list))
      } yield {
        val children = all.filter(_.parentConversationId.contains(parent._id))
        children.map(_._id).toSet shouldBe Set(childA._id, childB._id)
      }
    }
  }

  "Conversation.archived" should {
    "default to false" in {
      val conv = fresh()
      conv.archived shouldBe false
      rapid.Task.pure(succeed)
    }

    "round-trip through the store" in {
      val live = fresh(archived = false)
      val archived = fresh(archived = true)
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(live)))
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(archived)))
        loadedLive <- TestSigil.withDB(_.conversations.transaction(_.get(live._id)))
        loadedArchived <- TestSigil.withDB(_.conversations.transaction(_.get(archived._id)))
      } yield {
        loadedLive.map(_.archived) shouldBe Some(false)
        loadedArchived.map(_.archived) shouldBe Some(true)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
