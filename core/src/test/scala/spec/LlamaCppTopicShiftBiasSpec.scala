package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{TopicEntry, TopicShiftResult}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.llamacpp.LlamaCppProvider

/**
 * Live-LLM coverage for the topic classifier's placeholder bias. A
 * conversation-opening placeholder ("Greeting", the default seed)
 * followed by the conversation's first concrete subject must resolve
 * `Refine` (relabel in place), not `New` (mint a second topic) — the
 * conversation is finding its subject, not changing it. Without the
 * placeholder note in the classifier prompt, real models reliably
 * answer `New` and fragment a single thread of work across topics.
 *
 * The bias must not overshoot: a genuine subject change away from an
 * established topic still resolves `New`.
 */
class LlamaCppTopicShiftBiasSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  implicit override protected val testTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(5).minutes

  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id(sigil.provider.llamacpp.LlamaCpp.Provider, "qwen3.5-9b-q4_k_m")
  TestSigil.testModel(modelId)

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

  private def reseed(): Unit = {
    TestSigil.reset()
    TestSigil.setProvider(CachedProviderFixtures.wrap(this, Task(LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil))))
  }

  private def classify(currentLabel: String,
                       currentSummary: String,
                       proposedLabel: String,
                       proposedSummary: String,
                       userMessage: String): Task[TopicShiftResult] =
    TestSigil.classifyTopicShift(
      modelId = modelId,
      chain = List(TestUser, TestAgent),
      current = TopicEntry(
        id = sigil.conversation.Topic.id(s"topic-bias-${rapid.Unique()}"),
        label = currentLabel,
        summary = currentSummary
      ),
      priors = Nil,
      proposedLabel = proposedLabel,
      proposedSummary = proposedSummary,
      userMessage = userMessage
    )

  "topic classifier placeholder bias" should {

    "resolve Refine when the first concrete subject lands on a placeholder topic" in {
      reseed()
      classify(
        currentLabel = "Greeting",
        currentSummary = "Fresh conversation start — introducing myself as a Scala coding assistant.",
        proposedLabel = "sigil project setup",
        proposedSummary = "Binding the sigil workspace and starting Metals for semantic tools.",
        userMessage = "I'd like to start Metals for the sigil project."
      ).map { result =>
        result shouldBe TopicShiftResult.Refine
      }
    }

    "still resolve New for a genuine subject change away from established work" in {
      reseed()
      classify(
        currentLabel = "sigil project setup",
        currentSummary = "Metals is running for the sigil project; semantic tools are available.",
        proposedLabel = "Italy vacation planning",
        proposedSummary = "Planning a two-week trip through Rome, Florence, and the Amalfi coast.",
        userMessage = "Different subject — can you help me plan my vacation to Italy?"
      ).map { result =>
        result shouldBe TopicShiftResult.New
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
