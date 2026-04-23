package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, ConversationView, MemorySource, TurnInput}
import sigil.conversation.compression.{NoOpBlockExtractor, NoOpContextCompressor, Percentage, StandardContextCurator, StandardContextOptimizer, StandardMemoryRetriever}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.provider.{ConversationRequest, GenerationSettings, Instructions, Mode, ProviderEvent}
import sigil.provider.llamacpp.LlamaCppProvider
import sigil.tool.core.CoreTools
import sigil.tool.model.RespondInput
import sigil.vector.InMemoryVectorIndex

/**
 * End-to-end proof that stored memories surface back to the LLM on
 * future turns AND that the LLM actually uses them — the point of
 * the memory system.
 *
 * Flow:
 *   1. Wire TestSigil with the deterministic hash embedder + an
 *      [[InMemoryVectorIndex]], so [[sigil.Sigil.persistMemory]]
 *      auto-indexes and [[sigil.Sigil.searchMemories]] hits the
 *      vector branch.
 *   2. Persist a memory ("My favorite color is blue") — no recent
 *      frames reference it.
 *   3. Build a [[ConversationView]] whose latest frame is a user
 *      message with NO lexical overlap with the stored fact
 *      ("What is my favorite color?"). Only semantic similarity
 *      connects them.
 *   4. Run [[StandardContextCurator]] configured with a
 *      [[StandardMemoryRetriever]] pointing at the memory space.
 *   5. Assert the curator's output `TurnInput.memories` contains
 *      the memory id.
 *   6. Render the full [[ConversationRequest]] and send it to a live
 *      [[LlamaCppProvider]]; assert the model's `respond` call
 *      content mentions "blue" — concrete proof the memory reached
 *      the LLM AND the LLM used it.
 */
class MemoryRetrievalEndToEndSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")

  // Seed a Model record — curator always loads the target model.
  TestSigil.withDB(_.model.transaction(_.upsert(Model(
    canonicalSlug = "test/model",
    huggingFaceId = "",
    name = "Test Model",
    description = "",
    contextLength = 10_000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(10_000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  )))).sync()

  "StandardContextCurator + StandardMemoryRetriever" should {
    "surface a stored memory into TurnInput.memories and cause the LLM's response to reference the stored fact" in {
      TestSigil.reset()
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)

      val convId = Conversation.id(s"mret-${rapid.Unique()}")
      val fact = "My favorite color is blue."
      val persistedMemory = TestSigil.persistMemory(ContextMemory(
        fact = fact,
        source = MemorySource.Explicit,
        spaceId = MemoryTestSpace
      )).sync()

      // Build a view whose only frame is the user's question. Crucially
      // the question shares NO word stems with the stored fact except
      // the shared token "color" — the retriever still has to lean on
      // the embedder's similarity to surface it.
      val question = "What is my favorite color?"
      val view = ConversationView(
        conversationId = convId,
        frames = Vector(ContextFrame.Text(
          content = question,
          participantId = TestUser,
          sourceEventId = Id[Event]("q-1")
        )),
        _id = ConversationView.idFor(convId)
      )

      val curator = StandardContextCurator(
        sigil = TestSigil,
        optimizer = StandardContextOptimizer(),
        blockExtractor = NoOpBlockExtractor,
        memoryRetriever = StandardMemoryRetriever(spaces = Set(MemoryTestSpace), limit = 5),
        compressor = NoOpContextCompressor,
        budget = Percentage(0.8)
      )

      curator.curate(view, modelId, chain = List(TestUser, TestAgent)).flatMap { turnInput =>
        withClue(s"retrieved ids: ${turnInput.memories.map(_.value).mkString(",")}") {
          turnInput.memories should contain(persistedMemory._id)
        }

        val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
        val request = ConversationRequest(
          conversationId = convId,
          modelId = modelId,
          instructions = Instructions(),
          turnInput = turnInput,
          currentMode = Mode.Conversation,
          currentTopic = TestTopicEntry,
          generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0)),
          tools = CoreTools.all,
          chain = List(TestUser, TestAgent)
        )

        // Mechanical proof: the fact text appears in the wire body the
        // provider would send. Deterministic — no network I/O.
        val body = provider.requestConverter(request).sync().content match {
          case Some(c: spice.http.content.StringContent) => c.value
          case _ => ""
        }
        withClue(s"wire body:\n$body") {
          body should include(fact)
        }

        // Live-LLM proof: the model answers the question by referencing
        // the retrieved memory. The only source of "blue" in this turn
        // is the stored memory — if it shows up in the response, the
        // memory round-tripped end-to-end.
        provider(request).toList.map { events =>
          val respondInput = events.collectFirst {
            case ProviderEvent.ToolCallComplete(_, r: RespondInput) => r
          }
          val toolsSeen = events.collect { case s: ProviderEvent.ToolCallStart => s.toolName }.toSet
          withClue(s"tools observed in response: ${toolsSeen.mkString(",")}; respond content: ${respondInput.map(_.content).getOrElse("(none)")}") {
            respondInput should not be empty
            respondInput.get.content.toLowerCase should include("blue")
          }
        }
      }
    }
  }
}
