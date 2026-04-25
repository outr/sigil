package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextFrame, Conversation, ConversationView}
import sigil.conversation.compression.{Fixed, StandardContextCurator, StandardContextOptimizer, SummaryOnlyCompressor}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.provider.llamacpp.LlamaCppProvider

/**
 * End-to-end live-LLM test of the compression pipeline against
 * llama.cpp. A long synthetic conversation with a tight token budget
 * triggers [[SummaryOnlyCompressor]], which calls the model via
 * [[sigil.tool.consult.ConsultTool.invoke]], persists the resulting
 * [[sigil.conversation.ContextSummary]], and returns a TurnInput
 * whose `summaries` field carries the new id and whose frames are
 * trimmed to the newer half.
 */
class LlamaCppCompressionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id(sigil.provider.llamacpp.LlamaCpp.Provider, "qwen3.5-9b-q4_k_m")

  // Seed the model record so the curator's budget check can resolve it.
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

  "StandardContextCurator with SummaryOnlyCompressor (llama.cpp)" should {
    "fire compression when frames exceed the token budget, persist a summary, and swap it into TurnInput" in {
      TestSigil.reset()
      // Point providerFor at the real llama.cpp so ConsultTool.invoke can dispatch.
      val provider = LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)
      TestSigil.setProvider(Task.pure(provider))

      val curator = StandardContextCurator(
        sigil = TestSigil,
        optimizer = StandardContextOptimizer(),
        compressor = SummaryOnlyCompressor(),
        budget = Fixed(200) // deliberately tight so compression fires
      )

      val convId = Conversation.id(s"llamacpp-compression-${rapid.Unique()}")
      TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
        _id = convId, topics = List(TestTopicEntry)
      )))).sync()

      // Long synthetic conversation — 20 distinct exchanges, each tagged so
      // the compressor has real content to condense.
      val frames = (0 until 20).toVector.flatMap { i =>
        Vector(
          ContextFrame.Text(
            content = s"User turn $i: discussing topic A sub-point $i with concrete detail to preserve.",
            participantId = TestUser,
            sourceEventId = Id[Event](s"u-$i")
          ),
          ContextFrame.Text(
            content = s"Agent turn $i: acknowledged user's point $i and responded with supporting information.",
            participantId = TestAgent,
            sourceEventId = Id[Event](s"a-$i")
          )
        )
      }
      val view = ConversationView(
        conversationId = convId,
        frames = frames,
        _id = ConversationView.idFor(convId)
      )

      for {
        turnInput <- curator.curate(view, modelId, chain = List(TestUser, TestAgent))
        persistedSummaries <- TestSigil.summariesFor(convId)
      } yield {
        withClue(s"turnInput.summaries = ${turnInput.summaries}, frames size = ${turnInput.conversationView.frames.size}") {
          turnInput.summaries should have size 1
          turnInput.conversationView.frames.size should be < frames.size
        }
        val storedId = turnInput.summaries.head
        persistedSummaries.map(_._id) should contain(storedId)
        val summary = persistedSummaries.find(_._id == storedId).get
        summary.text.trim should not be empty
      }
    }
  }
}
