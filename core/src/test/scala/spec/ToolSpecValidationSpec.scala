package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.{
  ConsentSpec, DiscoverySpec, Effect, Execution, Freshness, MutationTargeting, OutputBounds,
  ProgressContract, ToolGates, ToolName, ToolProfile, ToolSpec, ToolSpecException
}

/**
 * ToolSpec is the single validation gate for tool metadata: apply
 * collects EVERY violation into one ToolSpecException instead of
 * failing on the first, so an author fixes the spec in one pass.
 */
class ToolSpecValidationSpec extends AnyWordSpec with Matchers {

  private val validProfile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable))

  "ToolSpec.apply" should {

    "construct a valid spec" in {
      val spec = ToolSpec(
        name = ToolName("valid_tool"),
        description = "Does a valid thing.",
        profile = validProfile,
        discovery = DiscoverySpec(keywords = Set("valid", "thing"))
      )
      spec.name.value shouldBe "valid_tool"
      spec.keywords shouldBe Set("valid", "thing")
    }

    "collect every violation into one exception" in {
      val ex = intercept[ToolSpecException] {
        ToolSpec(
          name = ToolName("broken_tool"),
          description = "   ",
          profile = ToolProfile(
            effect = Effect.Destructive(MutationTargeting.none, consequence = ""),
            execution = Execution.Detachable(keepRunningOnStop = false, progress = ProgressContract("")),
            gates = ToolGates(consent = Some(ConsentSpec("")))
          ),
          discovery = DiscoverySpec()
        )
      }
      ex.toolName shouldBe "broken_tool"
      ex.violations should have size 5
      ex.violations.exists(_.contains("description")) shouldBe true
      ex.violations.exists(_.contains("consequence")) shouldBe true
      ex.violations.exists(_.contains("ProgressContract")) shouldBe true
      ex.violations.exists(_.contains("consent")) shouldBe true
      ex.violations.exists(_.contains("keywords")) shouldBe true
      ex.getMessage should include("broken_tool")
    }

    "reject a description over the wire budget" in {
      val ex = intercept[ToolSpecException] {
        ToolSpec(
          name = ToolName("verbose_tool"),
          description = "x" * (ToolSpec.DescriptionBudget + 1),
          profile = validProfile,
          discovery = DiscoverySpec(keywords = Set("verbose"))
        )
      }
      ex.violations should have size 1
      ex.violations.head should include(ToolSpec.DescriptionBudget.toString)
    }

    "require keywords only for discoverable kinds" in {
      noException should be thrownBy ToolSpec(
        name = ToolName("internal_probe"),
        description = "Framework-internal sentinel.",
        profile = validProfile,
        discovery = DiscoverySpec(kind = sigil.tool.InternalKind)
      )
      noException should be thrownBy ToolSpec(
        name = ToolName("consult_probe"),
        description = "One-shot consult.",
        profile = validProfile,
        discovery = DiscoverySpec(kind = sigil.tool.consult.ConsultKind)
      )
      intercept[ToolSpecException] {
        ToolSpec(
          name = ToolName("builtin_probe"),
          description = "Discoverable but keyword-less.",
          profile = validProfile
        )
      }.violations.head should include("keywords")
    }

    "accept a SelfBounded profile with a consent gate carrying a prompt" in {
      val spec = ToolSpec(
        name = ToolName("gated_tool"),
        description = "Needs consent.",
        profile = ToolProfile(
          effect = Effect.Mutating(MutationTargeting.none),
          gates = ToolGates(consent = Some(ConsentSpec("May I run the gated tool?"))),
          output = OutputBounds.SelfBounded
        ),
        discovery = DiscoverySpec(keywords = Set("gated"))
      )
      spec.profile.gates.consent.map(_.prompt) shouldBe Some("May I run the gated tool?")
    }
  }
}
