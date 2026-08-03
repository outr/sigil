package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.db.Model
import sigil.provider.{InstructionTier, ModelProfile, PromptShape, Reliability}

/**
 * [[ModelProfile.heuristic]] is what a bare model id can honestly tell
 * the framework: an advertised parameter count, or membership in a
 * known frontier family. Everything else keeps the frontier default, so
 * a model nobody taught the framework about behaves exactly as before.
 */
class ModelProfileSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private def model(id: String): Model = TestSigil.testModel(Model.id(id))

  private def profile(id: String): ModelProfile = ModelProfile.heuristic(model(id))

  "ModelProfile.heuristic" should {

    "read a single-digit parameter count as a small model" in {
      val p = profile("qwen3.5-9b-q4_k_m")
      p.instructionTier shouldBe InstructionTier.Small
      p.promptShape shouldBe PromptShape.Compact
      p.toolCallReliability shouldBe Reliability.Wobbly
      p.needsOversight shouldBe false
    }

    "read a sub-4B parameter count as minimal, and arm oversight" in {
      val p = profile("llama3.2:3b")
      p.instructionTier shouldBe InstructionTier.Minimal
      p.promptShape shouldBe PromptShape.Compact
      p.needsOversight shouldBe true
    }

    "not be fooled by a mixture-of-experts multiplier or a quantization suffix" in {
      profile("mixtral-8x7b-instruct-q5_k_m").instructionTier shouldBe InstructionTier.Small
    }

    "treat a known frontier family as frontier" in {
      List("claude-opus-4", "gpt-5-mini", "gemini-2.5-pro", "grok-3").foreach { id =>
        withClue(s"$id: ") {
          val p = profile(id)
          p.instructionTier shouldBe InstructionTier.Frontier
          p.promptShape shouldBe PromptShape.Full
          p.toolCallReliability shouldBe Reliability.Solid
        }
      }
      succeed
    }

    "fall back to the unchanged default for an unrecognized id" in {
      val m = model("some-internal-finetune")
      ModelProfile.heuristic(m) shouldBe ModelProfile.default(m)
    }

    "leave a large open-weight model on the safe default" in {
      profile("gemma-4-26b") shouldBe ModelProfile.default(model("gemma-4-26b"))
    }

    "carry the model's own context length as its comfort" in {
      val m = model("qwen3.5-9b")
      ModelProfile.heuristic(m).contextComfort shouldBe m.contextLength.toInt
    }
  }

  "Sigil.modelProfileFor" should {
    "delegate to the heuristic by default" in {
      val m = model("llama3.2:3b")
      TestSigil.modelProfileFor(m) shouldBe ModelProfile.heuristic(m)
    }
  }
}
