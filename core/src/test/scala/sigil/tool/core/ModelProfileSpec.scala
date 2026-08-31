package sigil.tool.core

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.db.Model
import spec.TestSigil
import sigil.provider.*
import sigil.tool.discovery.{CapabilityMatch, CapabilityStatus, CapabilityType}

/**
 * `ModelProfile` declares what a model is behaviorally capable of so
 * the scaffolding stops carrying its own per-knob guesses. The default
 * profile must leave every existing deployment untouched.
 */
class ModelProfileSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val model = TestSigil.testModel(Model.id("anthropic", "claude-haiku-4-5"))

  private def profile(tier: InstructionTier,
                      comfort: Int = Int.MaxValue,
                      oversight: Boolean = false,
                      shape: PromptShape = PromptShape.Full) =
    ModelProfile(tier, Reliability.Solid, comfort, oversight, shape)

  private def matches(n: Int): List[CapabilityMatch] = (1 to n).toList.map { i =>
    CapabilityMatch(
      name = s"tool_$i",
      description = s"description for tool $i",
      capabilityType = CapabilityType.Tool,
      score = 100.0 - i,
      status = CapabilityStatus.Ready
    )
  }

  "The default profile" should {
    "treat an unrecognized model as frontier-tier and fully comfortable" in {
      val p = TestSigil.modelProfileFor(model)
      p.instructionTier shouldBe InstructionTier.Frontier
      p.toolCallReliability shouldBe Reliability.Solid
      p.contextComfort shouldBe model.contextLength.toInt
      p.needsOversight shouldBe false
      p.promptShape shouldBe PromptShape.Full
    }

    "leave the configured checkpoint and planner cadences unchanged" in {
      TestSigil.effectiveProgressCheckpointInterval(model._id) shouldBe TestSigil.progressCheckpointInterval
      TestSigil.effectivePlannerCadence(model._id) shouldBe TestSigil.plannerCadence
    }
  }

  "Instruction tier" should {
    "tighten cadence for small and minimal tiers only" in {
      InstructionTier.Frontier.cadenceTightening shouldBe 1
      InstructionTier.Capable.cadenceTightening shouldBe 1
      InstructionTier.Small.cadenceTightening shouldBe 2
      InstructionTier.Minimal.cadenceTightening shouldBe 4
    }

    "cap roster count for weak selectors only" in {
      InstructionTier.Frontier.rosterCountCeiling shouldBe None
      InstructionTier.Capable.rosterCountCeiling shouldBe None
      InstructionTier.Small.rosterCountCeiling shouldBe Some(8)
      InstructionTier.Minimal.rosterCountCeiling shouldBe Some(5)
    }
  }

  "Roster sizing" should {
    "size to contextComfort rather than the raw window when comfort is lower" in {
      val big = FindCapabilityTool.sizeToModel(matches(40), 200_000L, profile(InstructionTier.Frontier))
      val comfortable = FindCapabilityTool.sizeToModel(
        matches(40),
        200_000L,
        profile(InstructionTier.Frontier, comfort = 16_000))
      comfortable.size should be < big.size
    }

    "apply the tier's count ceiling for weak selectors" in {
      FindCapabilityTool.sizeToModel(matches(40), 200_000L, profile(InstructionTier.Small)).size shouldBe 8
      FindCapabilityTool.sizeToModel(matches(40), 200_000L, profile(InstructionTier.Minimal)).size shouldBe 5
    }

    "never trim below one match" in {
      FindCapabilityTool.sizeToModel(matches(3), 1_000L, profile(InstructionTier.Minimal)) should not be empty
    }
  }

  "PromptShape.Compact" should {
    "cap list-shaped section entries where Full does not" in {
      PromptShape.Full.entryCap shouldBe None
      PromptShape.Compact.entryCap shouldBe Some(5)
    }
  }
}
