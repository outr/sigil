package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{MemorySource, MemoryStatus}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.llamacpp.LlamaCppProvider

/**
 * Live-LLM coverage for [[sigil.Sigil.initializeMemories]]: passes
 * a list of declarative natural-language statements about a fictional
 * user, verifies the framework produces one keyed memory per
 * statement in the target space, marked `pinned` and `Approved`.
 *
 * The keys aren't asserted exactly because LLM-chosen identifiers
 * vary slightly across runs ("user.name" vs "user.first_name");
 * the spec asserts shape (count, pinning, status, source) and that
 * the canonical content for at least one statement landed.
 */
class LlamaCppInitializeMemoriesSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
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

  "Sigil.initializeMemories (llama.cpp)" should {
    "convert declarative statements into pinned, approved memories under the target space" in {
      TestSigil.reset()
      TestSigil.setProvider(Task.pure(LlamaCppProvider(TestSigil.llamaCppHost, Nil, TestSigil)))

      val statements = List(
        "My first name is Matt",
        "My last name is Hicks",
        "My email address is matt@outr.com",
        "I'm 46 years old"
      )

      TestSigil.initializeMemories(
        space      = MemoryTestSpace,
        statements = statements,
        modelId    = modelId,
        chain      = List(TestUser, TestAgent)
      ).flatMap { persisted =>
        TestSigil.findMemories(Set(MemoryTestSpace)).map { stored =>
          withClue(
            s"persisted=${persisted.size} stored=${stored.size} keys=${stored.flatMap(_.key).mkString(", ")}: "
          ) {
            // The LLM should produce one memory per declarative
            // statement. Allow ±1 since the model occasionally
            // collapses redundant phrasings or splits one into two.
            stored.size should be >= statements.size - 1
            stored.size should be <= statements.size + 1

            // Every initial memory is pinned + approved + UserInput-sourced
            // + scoped to the target space.
            stored.foreach { m =>
              m.pinned shouldBe true
              m.status shouldBe MemoryStatus.Approved
              m.source shouldBe MemorySource.UserInput
              m.spaceId shouldBe MemoryTestSpace
              m.key.exists(_.nonEmpty) shouldBe true
            }

            // Spot-check that the canonical content for the email
            // statement landed somewhere — the LLM normalises to
            // third-person but the value should be intact.
            val factText = stored.map(_.fact.toLowerCase).mkString(" | ")
            factText should include("matt@outr.com")
          }
        }
      }
    }
  }
}
