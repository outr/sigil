package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.Conversation
import sigil.event.Message
import sigil.signal.EventState
import sigil.tool.model.ResponseContent
import sigil.vector.InMemoryVectorIndex

/**
 * Exercises [[sigil.Sigil.searchConversationEvents]]'s vector branch:
 * publish Message events through the framework, auto-index via
 * wired vector provider, then semantic-search by query and verify
 * the right events surface.
 *
 * The Lucene fallback is covered by [[SearchConversationToolSpec]];
 * this one proves the vector-wired path.
 */
class SearchConversationEventsVectorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"sce-${rapid.Unique()}")
  private val otherConvId = Conversation.id(s"sce-other-${rapid.Unique()}")

  private def publishText(text: String, conv: lightdb.id.Id[Conversation]): Message = {
    val m = Message(
      participantId = TestUser,
      conversationId = conv,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(text)),
      state = EventState.Complete
    )
    TestSigil.publish(m).sync()
    m
  }

  "Sigil.searchConversationEvents" should {
    "retrieve semantically-related events via the vector branch" in {
      TestSigil.reset()
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)

      val matchMsg = publishText("We deployed the Qdrant vector store to staging yesterday.", convId)
      publishText("Lunch choices today: sushi, tacos, pizza.", convId)
      publishText("Completely unrelated topic about Qdrant and vector indexes.", otherConvId)

      TestSigil.searchConversationEvents(convId, "vector database deployment", limit = 5).map { hits =>
        val ids = hits.map(_._id)
        // The Qdrant-deployment message matches semantically; the lunch
        // one does not; the other-conversation one is scope-filtered.
        ids should contain(matchMsg._id)
      }
    }
  }
}
