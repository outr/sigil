package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.CoreContextValidator
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.workflow.WorkflowBuilderMode

/**
 * Regression for sigil bug #140 — `validateModeSkillSizes` used to
 * basis its per-skill ceiling on the smallest registered model's
 * context length. Complexity-routed apps register a small local
 * model alongside a frontier model exactly so the small one handles
 * `Complexity.Low` traffic; framework-bundled mode skills
 * (WorkflowBuilder, ScriptAuthoring, WebBrowser) overshoot the
 * resulting cap (10% × ~2820 = 282 tok) even though they fit
 * comfortably in the frontier ceiling (10% × 200K = 20K tok).
 *
 * Fix: largest-model context is the right basis. The modes whose
 * skills sit on the prompt always route to a frontier model;
 * complexity-tiered fallbacks are deliberately scoped to lighter
 * work that won't activate these skills.
 */
class ModeSkillShareLimitSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def model(slug: String, contextLength: Long): Model = Model(
    canonicalSlug       = slug,
    huggingFaceId       = "",
    name                = slug,
    displayName         = Some(slug),
    description         = "",
    contextLength       = contextLength,
    architecture        = ModelArchitecture(
      modality         = "text->text",
      inputModalities  = List("text"),
      outputModalities = List("text"),
      tokenizer        = "Unknown",
      instructType     = None
    ),
    pricing             = ModelPricing(
      prompt = BigDecimal(0), completion = BigDecimal(0),
      webSearch = None, inputCacheRead = None
    ),
    topProvider         = ModelTopProvider(
      contextLength       = Some(contextLength),
      maxCompletionTokens = None,
      isModerated         = false
    ),
    perRequestLimits    = None,
    supportedParameters = Set.empty,
    defaultParameters   = ModelDefaultParameters(),
    knowledgeCutoff     = None,
    expirationDate      = None,
    links               = ModelLinks(details = ""),
    created             = lightdb.time.Timestamp(),
    modified            = lightdb.time.Timestamp(),
    _id                 = Id[Model](slug)
  )

  "CoreContextValidator.largestModelContext" should {

    "return the model with the highest contextLength" in {
      val small = model("test/tiny",     2_820L)
      val mid   = model("test/mid",     32_768L)
      val large = model("test/frontier", 1_000_000L)
      for {
        _ <- TestSigil.cache.replace(List(small, mid, large))
      } yield {
        CoreContextValidator.largestModelContext(TestSigil).map(_._id) shouldBe Some(large._id)
        CoreContextValidator.smallestModelContext(TestSigil).map(_._id) shouldBe Some(small._id)
      }
    }

    "skip models with contextLength == 0 (catalog entries that didn't expose a size)" in {
      // DigitalOcean's /v1/models lists embedding / image models with
      // no context_length; those got contextLength = 0 in the registry.
      // Validators must not pick those as smallest / largest.
      val zero  = model("test/embed-zero",   0L)
      val small = model("test/llama-small",  4_096L)
      val large = model("test/opus-large",   200_000L)
      for {
        _ <- TestSigil.cache.replace(List(zero, small, large))
      } yield {
        CoreContextValidator.largestModelContext(TestSigil).map(_._id) shouldBe Some(large._id)
        CoreContextValidator.smallestModelContext(TestSigil).map(_._id) shouldBe Some(small._id)
      }
    }
  }

  "framework-bundled WorkflowBuilder skill" should {

    "fit under modeSkillShareLimit (10%) when a small + large model are both registered" in {
      // The previous behaviour used minBy, yielding limit = 282 tok
      // against a 1382-tok skill — boot threw. Largest-model basis
      // yields ~20K tok limit which the skill comfortably clears.
      val small = model("test/llama-small",  2_820L)
      val large = model("test/opus-large",   200_000L)
      for {
        _ <- TestSigil.cache.replace(List(small, large))
      } yield {
        val largest = CoreContextValidator.largestModelContext(TestSigil).get
        val limit   = (largest.contextLength.toDouble * TestSigil.modeSkillShareLimit).toInt
        val skillText = WorkflowBuilderMode.skill.get.content
        val tokens    = sigil.tokenize.HeuristicTokenizer.count(skillText)
        withClue(s"WorkflowBuilder skill: $tokens tok / limit $limit") {
          tokens should be < limit
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
