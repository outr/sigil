package sigil.provider.deepinfra

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec

/**
 * Coverage for sigil bug #162 — translating a DeepInfra `/models/list`
 * row into a Sigil [[sigil.db.Model]] with correct pricing, context
 * length, and modality. Drives [[DeepInfra.toModel]] against the
 * canonical kimi-k2.5 wire shape — no network required.
 */
class DeepInfraCatalogSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  spec.TestSigil.initFor(getClass.getSimpleName)

  // Verbatim shape from `https://api.deepinfra.com/models/list` for
  // `moonshotai/Kimi-K2.5` (captured 2026-05-12). cents_per_*_token
  // values translate to $0.45/M input, $2.25/M output, $0.07/M cached.
  private val kimiK25Json =
    """{
      |  "model_name": "moonshotai/Kimi-K2.5",
      |  "type": "text-generation",
      |  "description": "Kimi K2.5 (Reasoning)",
      |  "max_tokens": 262144,
      |  "tags": ["json", "reasoning", "tools", "openai", "structured-output", "multimodal", "featured"],
      |  "pricing": {
      |    "type": "tokens",
      |    "cents_per_input_token": 4.5e-05,
      |    "cents_per_output_token": 0.000225,
      |    "rate_per_input_token_cached": 0.15555556,
      |    "rate_per_input_token_cache_write": null
      |  },
      |  "deprecated": null,
      |  "quantization": "fp4"
      |}""".stripMargin

  private def parseRow(json: String): DeepInfra.Entry = {
    val raw = JsonParser(json)
    val normalized = raw.filterOne(fabric.filter.SnakeToCamelFilter)
    import fabric.rw.*
    normalized.as[DeepInfra.Entry]
  }

  "DeepInfra.toModel (Bug #162)" should {

    "translate kimi-k2.5 catalog row into a Sigil Model with correct pricing" in {
      val entry = parseRow(kimiK25Json)
      val model = DeepInfra.toModel(entry)

      model.canonicalSlug shouldBe "deepinfra/moonshotai/Kimi-K2.5"
      model._id.value shouldBe "deepinfra/moonshotai/kimi-k2.5"
      model.name shouldBe "moonshotai/Kimi-K2.5"
      model.contextLength shouldBe 262144L

      // cents/token → USD/token: divide by 100.
      //   4.5e-05 / 100 = 4.5e-07 USD/token = $0.45/M
      model.pricing.prompt shouldBe BigDecimal("4.5E-7")
      //   0.000225 / 100 = 2.25e-06 USD/token = $2.25/M
      model.pricing.completion shouldBe BigDecimal("2.25E-6")
      //   rate (0.15555556) × input rate ($4.5e-07) = $7.0e-08/token = ~$0.07/M
      model.pricing.inputCacheRead.isDefined shouldBe true
      val cached = model.pricing.inputCacheRead.get
      // Expect ~7e-08, exact value depends on multiplication precision.
      (cached * BigDecimal(1_000_000)).toDouble shouldBe (0.07 +- 0.001)

      model.architecture.modality shouldBe "text+image->text"
      model.architecture.inputModalities should contain ("text")
      model.architecture.inputModalities should contain ("image")
      model.supportedParameters should contain ("tools")
      model.supportedParameters should contain ("tool_choice")
      model.expirationDate shouldBe None
      succeed
    }

    "mark a deprecated row's expirationDate from the unix epoch" in {
      val deprecatedJson = kimiK25Json.replace("\"deprecated\": null", "\"deprecated\": 1776286096")
      val entry = parseRow(deprecatedJson)
      val model = DeepInfra.toModel(entry)
      model.expirationDate.isDefined shouldBe true
      model.expirationDate.get.value shouldBe (1776286096L * 1000)
      succeed
    }

    "fall back to text-only modality when no multimodal/vision tag is present" in {
      val plainJson = kimiK25Json.replace(
        "\"json\", \"reasoning\", \"tools\", \"openai\", \"structured-output\", \"multimodal\", \"featured\"",
        "\"json\", \"reasoning\", \"tools\", \"openai\""
      )
      val entry = parseRow(plainJson)
      val model = DeepInfra.toModel(entry)
      model.architecture.modality shouldBe "text->text"
      model.architecture.inputModalities shouldBe List("text")
      succeed
    }

    "omit cached-read pricing when the catalog row doesn't publish a rate" in {
      val noCacheJson = kimiK25Json.replace(
        "\"rate_per_input_token_cached\": 0.15555556,",
        "\"rate_per_input_token_cached\": null,"
      )
      val entry = parseRow(noCacheJson)
      val model = DeepInfra.toModel(entry)
      model.pricing.inputCacheRead shouldBe None
      succeed
    }

    "produce zero pricing on a row missing the pricing block (don't crash)" in {
      val noPricing = """{"model_name":"x/y","type":"text-generation","max_tokens":1000}"""
      val entry = parseRow(noPricing)
      val model = DeepInfra.toModel(entry)
      model.pricing.prompt shouldBe BigDecimal(0)
      model.pricing.completion shouldBe BigDecimal(0)
      model.pricing.inputCacheRead shouldBe None
      succeed
    }
  }

  "tear down" should {
    "dispose TestSigil" in spec.TestSigil.shutdown.map(_ => succeed)
  }
}
