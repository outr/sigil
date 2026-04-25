package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, MemoryStatus}
import sigil.conversation.compression.extract.{DefaultHighSignalFilter, StandardMemoryExtractor}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.vector.InMemoryVectorIndex

/**
 * Live-LLM coverage for [[StandardMemoryExtractor]]: runs the
 * extractor against a real llama.cpp instance with a high-signal
 * user turn, asserts at least one keyed memory lands in the
 * compression space with `Pending` status (the default for auto-
 * extracted facts). Complements
 * [[LlamaCppMemoryExtractionSpec]], which covers the compression-
 * time pathway.
 */
class LlamaCppPerTurnExtractionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
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

  "StandardMemoryExtractor (llama.cpp)" should {
    "persist a keyed memory from a high-signal user turn against a real LLM" in {
      TestSigil.reset()
      TestSigil.setProvider(Task.pure(LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)))
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)

      val convId = Conversation.id(s"per-turn-extract-${rapid.Unique()}")
      TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
        _id = convId, topics = List(TestTopicEntry)
      )))).sync()

      val extractor = StandardMemoryExtractor(
        filter = DefaultHighSignalFilter,
        spaceIdFor = _ => Task.pure(Some(MemoryTestSpace))
      )

      val userMessage =
        "Hi! My name is Alice, I live in Brooklyn, I prefer dark-mode interfaces, and my preferred programming language is Scala."
      val agentResponse =
        "Got it — noted Alice, in Brooklyn, prefers dark mode and Scala."

      extractor
        .extract(
          sigil = TestSigil,
          conversationId = convId,
          modelId = modelId,
          chain = List(TestUser, TestAgent),
          userMessage = userMessage,
          agentResponse = agentResponse
        )
        .flatMap { persisted =>
          TestSigil
            .findMemories(Set(MemoryTestSpace))
            .map(_.filter(_.conversationId.contains(convId)))
            .map { stored =>
              withClue(s"extractor returned ${persisted.size} entries: ${persisted.map(_.key).mkString(", ")} | persisted: ${stored.map(_.key).mkString(", ")}") {
                stored should not be empty
                // Auto-extracted facts land as Pending by default
                stored.foreach(_.status shouldBe MemoryStatus.Pending)
                // At least one key should relate to a durable fact
                // the model saw (name / location / preference). Accept
                // any of the canonical shapes the LLM might produce.
                val keys = stored.map(_.key.toLowerCase)
                val looksDurable = keys.exists(k =>
                  k.contains("name") || k.contains("location") ||
                  k.contains("prefer") || k.contains("language") ||
                  k.contains("theme") || k.contains("user")
                )
                looksDurable shouldBe true
              }
            }
        }
    }
  }
}
