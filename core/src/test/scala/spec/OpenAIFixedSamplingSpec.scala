package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.openai.OpenAIProvider
import sigil.provider.{GenerationSettings, OneShotRequest}

/**
 * Regression for bug #45 — the OpenAI provider must NOT send
 * `temperature` / `top_p` to models whose catalog entry doesn't
 * declare those parameters as supported (GPT-5 family, reasoning-
 * only o1 / o3 lineage).
 *
 * The filter is driven primarily by
 * [[sigil.Sigil.supportsParameter]] reading
 * [[sigil.db.Model.supportedParameters]] from the registry; the
 * provider falls back to a known-prefix list when the registry is
 * cold (offline boot, pre-refresh).
 */
class OpenAIFixedSamplingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val provider = OpenAIProvider(apiKey = "sk-test-placeholder", sigilRef = TestSigil)

  private def fakeModel(provider: String,
                        model: String,
                        supports: Set[String]): Model = Model(
    canonicalSlug = s"$provider/$model",
    huggingFaceId = "",
    name = s"$provider/$model",
    description = "fake",
    contextLength = 128_000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "BPE",
      instructType = None
    ),
    pricing = ModelPricing(prompt = 0, completion = 0, webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(128_000L), maxCompletionTokens = Some(16_000L), isModerated = false),
    perRequestLimits = None,
    supportedParameters = supports,
    defaultParameters = ModelDefaultParameters(),
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(0L),
    _id = Model.id(provider, model)
  )

  private def bodyOf(req: OneShotRequest): String =
    provider.requestConverter(req).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    }

  private def request(modelId: Id[Model]): OneShotRequest = OneShotRequest(
    modelId      = modelId,
    systemPrompt = "Be terse.",
    userPrompt   = "Hi.",
    generationSettings = GenerationSettings(temperature = Some(0.0), topP = Some(0.5), maxOutputTokens = Some(50))
  )

  "OpenAIProvider with a model that supports temperature" should {
    "include temperature + top_p in the wire payload" in {
      val m = fakeModel("openai", "gpt-4o-coverage", supports = Set("temperature", "top_p", "tools"))
      TestSigil.cache.merge(List(m)).map { _ =>
        val body = bodyOf(request(m._id))
        body should include("\"temperature\":")
        body should include("\"top_p\":")
        body should include("\"max_output_tokens\":")
      }
    }
  }

  "OpenAIProvider with a model whose supportedParameters omit temperature" should {
    "drop temperature + top_p (driven by Model.supportedParameters)" in {
      // Empty `Set("max_output_tokens")` — temperature is NOT in the
      // model's declared supported set. The provider must skip it
      // even though the request asked for `temperature = 0.0`.
      val m = fakeModel("openai", "gpt-5-fixed-cov", supports = Set("max_output_tokens"))
      TestSigil.cache.merge(List(m)).map { _ =>
        val body = bodyOf(request(m._id))
        body should not include "\"temperature\":"
        body should not include "\"top_p\":"
        // Other settings still flow through.
        body should include("\"max_output_tokens\":")
      }
    }
  }

  "OpenAIProvider on a cold cache" should {
    "fall back to the gpt-5 prefix safety net (no model record present)" in rapid.Task {
      // Use a never-cached model id whose name starts with `gpt-5`.
      val coldId = Model.id("openai", s"gpt-5-uncached-${rapid.Unique()}")
      val body = bodyOf(request(coldId))
      body should not include "\"temperature\":"
      body should not include "\"top_p\":"
      succeed
    }

    "fall back to the o1 prefix safety net" in rapid.Task {
      val coldId = Model.id("openai", s"o1-mini-uncached-${rapid.Unique()}")
      val body = bodyOf(request(coldId))
      body should not include "\"temperature\":"
      body should not include "\"top_p\":"
      succeed
    }

    "let temperature through for an unrelated cold model name (no false positives)" in rapid.Task {
      // Model isn't in the cache and prefix doesn't match — fail-open
      // posture means we still send temperature.
      val coldId = Model.id("openai", s"gpt-4-uncached-${rapid.Unique()}")
      val body = bodyOf(request(coldId))
      body should include("\"temperature\":")
      succeed
    }
  }

  "Sigil.supportsParameter" should {
    "return true for an uncached model (fail-open posture)" in rapid.Task {
      TestSigil.supportsParameter(Model.id("openai", s"never-seen-${rapid.Unique()}"), "temperature") shouldBe true
      succeed
    }

    "return true for a cached model with empty supportedParameters (no info → don't filter)" in {
      val m = fakeModel("openai", "param-empty", supports = Set.empty)
      TestSigil.cache.merge(List(m)).map { _ =>
        TestSigil.supportsParameter(m._id, "temperature") shouldBe true
      }
    }

    "filter from supportedParameters when the cached model lists capabilities" in {
      val m = fakeModel("openai", "param-strict", supports = Set("max_output_tokens"))
      TestSigil.cache.merge(List(m)).map { _ =>
        TestSigil.supportsParameter(m._id, "temperature") shouldBe false
        TestSigil.supportsParameter(m._id, "max_output_tokens") shouldBe true
      }
    }
  }
}
