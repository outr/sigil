package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.cache.ModelRegistry
import sigil.db.Model

/**
 * Sigil #374 — `findTolerant` must rescue a registered model whose looked-up
 * id differs only by case, provider prefix, or `.`-vs-`-` separators, so a
 * planner-authored step id like `claude-3-5-sonnet` resolves to the registered
 * `anthropic/claude-3.5-sonnet` instead of throwing and killing the run. This
 * normalization also backs the tolerant rescue in `Sigil.resolveProviderModel`.
 */
class ModelRegistryToleranceSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def model(provider: String, name: String): Model =
    TestSigil.testModel(Model.id(provider, name))

  private val reg = new ModelRegistry
  reg.merge(List(model("anthropic", "claude-3.5-sonnet"), model("openai", "gpt-5.5"))).sync()

  private def resolved(id: String): Option[String] =
    reg.findTolerant(Id[Model](id)).map(_._id.value)

  "ModelRegistry.findTolerant (sigil #374)" should {

    "match an exact id" in {
      resolved("anthropic/claude-3.5-sonnet") shouldBe Some("anthropic/claude-3.5-sonnet")
    }

    "rescue a bare id with dash-for-dot separators" in {
      // The reported case: planner free-formed `claude-3-5-sonnet`.
      resolved("claude-3-5-sonnet") shouldBe Some("anthropic/claude-3.5-sonnet")
    }

    "rescue across case and prefix" in {
      resolved("Anthropic/Claude-3.5-Sonnet") shouldBe Some("anthropic/claude-3.5-sonnet")
    }

    "rescue a bare prefixless id for another provider" in {
      resolved("gpt-5-5") shouldBe Some("openai/gpt-5.5")
    }

    "still return None for a genuinely absent model" in {
      resolved("mistral-large-2") shouldBe None
    }
  }
}
