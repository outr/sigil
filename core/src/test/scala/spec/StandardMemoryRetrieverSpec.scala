package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, ConversationView, MemorySource}
import sigil.conversation.compression.StandardMemoryRetriever
import sigil.event.Event
import sigil.vector.InMemoryVectorIndex

/**
 * Covers [[StandardMemoryRetriever]]'s two-bucket output: always-on
 * Critical-source records vs query-driven semantic matches.
 */
class StandardMemoryRetrieverSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"mret-spec-${rapid.Unique()}")

  private def viewWithQuestion(q: String): ConversationView =
    ConversationView(
      conversationId = convId,
      frames = Vector(ContextFrame.Text(q, TestUser, Id[Event](s"q-${rapid.Unique()}"))),
      _id = ConversationView.idFor(convId)
    )

  "StandardMemoryRetriever" should {
    "surface Critical memories unconditionally AND query-relevant ones separately (no duplication)" in {
      TestSigil.reset()
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)

      val critical = TestSigil.persistMemory(ContextMemory(
        fact = "The user must never be given financial advice.",
        source = MemorySource.Critical,
        spaceId = MemoryTestSpace
      )).sync()
      val relevant = TestSigil.persistMemory(ContextMemory(
        fact = "The user's favorite color is blue.",
        source = MemorySource.Explicit,
        spaceId = MemoryTestSpace
      )).sync()
      val unrelated = TestSigil.persistMemory(ContextMemory(
        fact = "The user's pet's name is Biscuit.",
        source = MemorySource.Explicit,
        spaceId = MemoryTestSpace
      )).sync()

      val retriever = StandardMemoryRetriever(spaces = Set(MemoryTestSpace), limit = 3)
      retriever.retrieve(
        sigil = TestSigil,
        view = viewWithQuestion("What is my favorite color?"),
        chain = List(TestUser, TestAgent)
      ).map { result =>
        // Critical record always surfaces, regardless of the question.
        result.criticalMemories should contain(critical._id)
        // The color question finds the color fact.
        result.memories should contain(relevant._id)
        // The critical record is NOT duplicated into memories.
        result.memories should not contain critical._id
      }
    }

  }
}
