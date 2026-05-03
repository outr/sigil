package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextMemory, MemorySource}
import sigil.db.Model
import sigil.provider.CoreContextOverflowException

/**
 * Coverage for `Sigil.coreContextShareLimit` — the write-time cap that
 * prevents Critical-memory persists from pushing the inviolable share
 * past a configurable fraction of the model's window. The cap exists
 * so the framework's auto-shedding always has room; testing it both
 * confirms enforcement AND validates the diagnostic-payload shape
 * downstream consumers will catch.
 */
class CoreContextCapSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  // Seed the model registry with a small-context model so the cap kicks in
  // at conversational-memory sizes. 4000 tokens × 0.5 = 2000-token cap on
  // the inviolable share. The validator reserves 500 tokens for static
  // system-prompt overhead → ~1500 tokens left for Critical memory text.
  // A small directive (~10 tok) easily fits; a 600-token verbose one
  // crosses the line.
  private val tinyModel: Model = Model(
    canonicalSlug = "test/tiny",
    huggingFaceId = "",
    name = "tiny",
    description = "Synthetic small-context model for cap tests",
    contextLength = 4000L,
    architecture = sigil.db.ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = sigil.db.ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = sigil.db.ModelTopProvider(contextLength = Some(1000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = sigil.db.ModelLinks(details = ""),
    created = lightdb.time.Timestamp(),
    _id = Id[Model]("test/tiny")
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSigil.cache.merge(List(tinyModel)).sync()
  }

  private def critical(key: String, fact: String, summary: String = ""): ContextMemory =
    ContextMemory(
      fact = fact,
      source = MemorySource.Critical,
      spaceId = TestSpace,
      key = key,
      summary = summary
    )

  "Sigil.persistMemory with MemorySource.Critical" should {

    "succeed when the memory fits under the cap" in {
      val small = critical(s"cap-fit-${rapid.Unique()}", "Brief directive that fits easily.")
      TestSigil.persistMemory(small).map { _ =>
        succeed
      }
    }

    "reject with CoreContextOverflowException when over the cap" in {
      // 4000 tokens × 0.5 = 2000-token core-context cap. Subtract the
      // 500-token system-prompt overhead constant → 1500 left for
      // Critical memories. A 1800-token (huge) directive crosses
      // the line.
      val oversized = critical(
        key = s"cap-oversized-${rapid.Unique()}",
        fact = ("This is a critical directive that requires significant explanation across multiple sentences. " * 200).trim
      )
      TestSigil.persistMemory(oversized).attempt.map {
        case scala.util.Failure(e: CoreContextOverflowException) =>
          e.modelId.value shouldBe "test/tiny"
          e.limit should be > 0
          e.wouldBeTotal should be > e.limit
          succeed
        case scala.util.Failure(other) =>
          fail(s"Expected CoreContextOverflowException, got ${other.getClass.getSimpleName}: ${other.getMessage}")
        case scala.util.Success(_) =>
          fail("Expected CoreContextOverflowException, but persist succeeded")
      }
    }

    "honour `summary || fact` when computing the cost" in {
      // Same fact text — but the summary is short. With the renderer
      // using `summary || fact`, the cap should be computed against
      // the summary, which fits.
      val withSummary = critical(
        key = s"cap-with-summary-${rapid.Unique()}",
        fact = ("Long verbose fact text. " * 100).trim,
        summary = "Tight one-line directive."
      )
      TestSigil.persistMemory(withSummary).map(_ => succeed)
    }
  }

  "Sigil.persistMemory with non-Critical source" should {
    "skip the cap check" in {
      // Same oversized text, but source = Compression — sheddable, so
      // not subject to the inviolable cap.
      val sheddable = ContextMemory(
        fact = ("Big sheddable memory. " * 100).trim,
        source = MemorySource.Compression,
        spaceId = TestSpace,
        key = s"non-critical-${rapid.Unique()}"
      )
      TestSigil.persistMemory(sheddable).map(_ => succeed)
    }
  }
}
