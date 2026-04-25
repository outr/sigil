package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextFrame, Conversation, ConversationView, MemorySource}
import sigil.conversation.compression.MemoryContextCompressor
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.vector.InMemoryVectorIndex

/**
 * End-to-end live-LLM coverage of
 * [[sigil.conversation.compression.MemoryContextCompressor]]:
 *
 *   1. Wire TestSigil with vector search + a compression memory space.
 *   2. Feed the compressor a synthetic conversation containing a
 *      durable fact.
 *   3. The compressor runs two live llama.cpp calls (extract, then
 *      summarize), persists the extracted fact as a
 *      [[sigil.conversation.ContextMemory]], and persists the
 *      summary.
 *   4. Assert [[sigil.Sigil.searchMemories]] retrieves the extracted
 *      fact by semantic query — the retrieval-end-to-end property
 *      that justifies extraction in the first place.
 */
class LlamaCppMemoryExtractionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id(sigil.provider.llamacpp.LlamaCpp.Provider, "qwen3.5-9b-q4_k_m")

  TestSigil.cache.replace(List(Model(
    canonicalSlug = s"${sigil.provider.llamacpp.LlamaCpp.Provider}/qwen3.5-9b-q4_k_m",
    huggingFaceId = "",
    name = "qwen3.5-9b-q4_k_m",
    description = "Test seed",
    contextLength = 262_144L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(262_144L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  ))).sync()

  "MemoryContextCompressor (llama.cpp)" should {
    "extract durable facts into the memory space and make them retrievable via searchMemories" in {
      TestSigil.reset()
      TestSigil.setProvider(Task.pure(LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)))
      TestSigil.setCompressionSpace(Some(MemoryTestSpace))
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)

      val convId = Conversation.id(s"mem-extract-${rapid.Unique()}")
      TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
        _id = convId, topics = List(TestTopicEntry)
      )))).sync()

      val frames = Vector(
        ContextFrame.Text(
          content = "Hi, my name is Alice and I prefer dark-mode interfaces in every app I use.",
          participantId = TestUser,
          sourceEventId = Id[Event]("u-1")
        ),
        ContextFrame.Text(
          content = "Noted — I'll keep that preference in mind for later.",
          participantId = TestAgent,
          sourceEventId = Id[Event]("a-1")
        ),
        ContextFrame.Text(
          content = "Also, our deployment target is staging.us-east-1 — important for anything infrastructure-related.",
          participantId = TestUser,
          sourceEventId = Id[Event]("u-2")
        ),
        ContextFrame.Text(
          content = "Got it: staging.us-east-1 for deployments.",
          participantId = TestAgent,
          sourceEventId = Id[Event]("a-2")
        )
      )

      val compressor = MemoryContextCompressor()

      for {
        result <- compressor.compress(TestSigil, modelId, chain = List(TestUser, TestAgent), frames, convId)
        memoriesInSpace <- TestSigil.withDB(_.memories.transaction(_.list))
          .map(_.filter(_.spaceId == MemoryTestSpace))
        darkModeHits <- TestSigil.searchMemories("what color theme does alice prefer", Set(MemoryTestSpace), limit = 5)
      } yield {
        withClue(s"compressor summary: ${result.map(_.text).getOrElse("(none)")}, memories: ${memoriesInSpace.map(_.fact).mkString(" | ")}") {
          // Compressor returned a summary
          result should not be None
          // At least one extracted memory landed in the space
          memoriesInSpace should not be empty
          memoriesInSpace.exists(_.source == MemorySource.Compression) shouldBe true
          // And at least one memory body mentions dark mode — the model may
          // phrase it as "prefers dark mode" / "prefers dark-mode interfaces"
          // / "uses dark mode"; accept any phrasing.
          memoriesInSpace.exists(_.fact.toLowerCase.contains("dark")) shouldBe true
        }
        // Retrieval: querying for "color theme" should surface the dark-mode fact.
        withClue(s"retrieval hits: ${darkModeHits.map(_.fact).mkString(" | ")}") {
          darkModeHits.exists(_.fact.toLowerCase.contains("dark")) shouldBe true
        }
      }
    }
  }
}
