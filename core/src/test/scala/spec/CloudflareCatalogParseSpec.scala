package spec

import fabric.Str
import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.cloudflare.Cloudflare

/**
 * Regression for sigil #331 — Cloudflare's `/ai/models/search` returns
 * model `properties` whose `value` is not always a string (`price` is a
 * JSON array of per-unit pricing objects). Typing `Property.value` as
 * `String` made fabric's strict decode throw on the array, and because
 * the parse was all-or-nothing wrapped in `Try(...).getOrElse(empty)`,
 * one array-valued property on one model collapsed the entire catalog to
 * zero models — silently. Kimi K2.6 (which carries a `price`) dropped out
 * of the routing chain as a result.
 *
 * The fix tolerates non-string property values and decodes entries
 * individually so a single malformed row skips itself rather than
 * dropping the page.
 */
class CloudflareCatalogParseSpec extends AnyWordSpec with Matchers {

  /** A `/models/search` page mirroring the live wire shape: one model
    * with an array-valued `price` (Kimi), one all-string model, and one
    * structurally-broken row (missing the required `name`). */
  private val page = JsonParser(
    """
      |{
      |  "success": true,
      |  "result_info": {"page": 1, "per_page": 50, "count": 3, "total_count": 3},
      |  "result": [
      |    {
      |      "id": "uuid-kimi",
      |      "name": "@cf/moonshotai/kimi-k2.6",
      |      "description": "Kimi K2.6",
      |      "task": {"id": "t1", "name": "Text Generation"},
      |      "properties": [
      |        {"property_id": "context_window", "value": "131072"},
      |        {"property_id": "function_calling", "value": "true"},
      |        {"property_id": "price", "value": [
      |          {"unit": "per M input tokens", "price": 0.95, "currency": "USD"},
      |          {"unit": "per M output tokens", "price": 4, "currency": "USD"}
      |        ]}
      |      ]
      |    },
      |    {
      |      "id": "uuid-llama",
      |      "name": "@cf/meta/llama-3.1-8b",
      |      "task": {"id": "t1", "name": "Text Generation"},
      |      "properties": [
      |        {"property_id": "context_window", "value": "8192"}
      |      ]
      |    },
      |    {
      |      "id": "uuid-broken",
      |      "task": {"id": "t1", "name": "Text Generation"},
      |      "properties": []
      |    }
      |  ]
      |}
      |""".stripMargin
  )

  "Cloudflare.parsePage" should {
    "keep the array-valued `price` model instead of collapsing the catalog" in {
      val (entries, _) = Cloudflare.parsePage(page)
      entries.map(_.name) should contain("@cf/moonshotai/kimi-k2.6")
    }

    "skip the structurally-broken row without dropping the well-formed ones" in {
      val (entries, _) = Cloudflare.parsePage(page)
      // Kimi + llama survive; the name-less row is skipped.
      entries.map(_.name).toSet shouldBe Set("@cf/moonshotai/kimi-k2.6", "@cf/meta/llama-3.1-8b")
    }

    "preserve the non-string `price` value as JSON on the entry" in {
      val (entries, _) = Cloudflare.parsePage(page)
      val kimi = entries.find(_.name == "@cf/moonshotai/kimi-k2.6").getOrElse(fail("kimi not parsed"))
      val price = kimi.properties.find(_.propertyId == "price").getOrElse(fail("price property missing"))
      price.value.isArr shouldBe true
    }

    "surface pagination info" in {
      val (_, info) = Cloudflare.parsePage(page)
      info.map(_.totalCount) shouldBe Some(3)
    }
  }

  "Cloudflare.toModel" should {
    "read the string-valued properties and ignore the array `price`" in {
      val (entries, _) = Cloudflare.parsePage(page)
      val kimi  = Cloudflare.toModel(entries.find(_.name == "@cf/moonshotai/kimi-k2.6").get)
      kimi.contextLength shouldBe 131072L
      kimi.supportedParameters should contain("tools")  // function_calling = "true"
      // Pricing stays at zero (catalog price is per-neuron, not per-token).
      kimi.pricing.prompt shouldBe BigDecimal(0)
    }

    "leave property string extraction empty when a value isn't a string" in {
      // Defensive: a non-string property never leaks a stringified form
      // into the consumed map.
      val (entries, _) = Cloudflare.parsePage(page)
      val kimi = entries.find(_.name == "@cf/moonshotai/kimi-k2.6").get
      kimi.properties.collect { case Cloudflare.Property(id, Str(s, _)) => id }.toSet shouldBe
        Set("context_window", "function_calling")
    }
  }
}
