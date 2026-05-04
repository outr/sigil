package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.AnalysisWork
import sigil.role.Role
import sigil.workflow.{AgentDecisionStepInput, SigilAgentDecisionStep}

/**
 * Pure-logic coverage for [[SigilAgentDecisionStep]]'s loop helpers
 * — completion detection, prompt assembly, prior-reasoning fold-in.
 *
 * End-to-end (real LLM round-trip + Strider step append + worker
 * conv linkage) lives in the integration suite; this spec locks
 * the local decisions so we can iterate the helpers without
 * paying for live calls.
 */
class AgentDecisionStepLogicSpec extends AnyWordSpec with Matchers {

  private val sampleInput = AgentDecisionStepInput(
    id      = "decision-0",
    role    = Role(name = "researcher", description = "Research and synthesize.", workType = AnalysisWork),
    brief   = "Find recent papers on RAG",
    modelId = "anthropic/claude-sonnet-4-6"
  )

  "parseCompletion" should {
    "return None when the response has no completion marker" in {
      SigilAgentDecisionStep.parseCompletion("Just thinking about it...") shouldBe None
    }

    "extract the post-marker text on its own line" in {
      val response = "I considered several angles.\nComplete: Found 3 RAG papers from 2026."
      SigilAgentDecisionStep.parseCompletion(response) shouldBe Some("Found 3 RAG papers from 2026.")
    }

    "be case-insensitive on the marker" in {
      val response = "Some reasoning.\ncomplete: done."
      SigilAgentDecisionStep.parseCompletion(response) shouldBe Some("done.")
    }

    "ignore mid-paragraph mentions of 'Complete:' (anchored at line start only)" in {
      val response = "The work is not Complete: yet — still need to verify."
      SigilAgentDecisionStep.parseCompletion(response) shouldBe None
    }

    "capture multi-line summary text following the marker" in {
      val response =
        """Reasoning step.
          |Complete: Summary line 1.
          |Continued summary line 2.""".stripMargin
      SigilAgentDecisionStep.parseCompletion(response) shouldBe Some("Summary line 1.\nContinued summary line 2.")
    }
  }

  "buildSystemPrompt" should {
    "include the role description and the iteration context" in {
      val sys = SigilAgentDecisionStep.buildSystemPrompt(sampleInput)
      sys should include("Research and synthesize.")
      sys should include("iteration 1 of up to 50")
      sys should include("Complete:")
    }

    "advance the iteration display when the input is on a later turn" in {
      val later = sampleInput.copy(iteration = 4, maxIterations = 10)
      val sys = SigilAgentDecisionStep.buildSystemPrompt(later)
      sys should include("iteration 5 of up to 10")
    }
  }

  "buildUserPrompt" should {
    "be exactly the brief on the first iteration (no prior reasoning)" in {
      SigilAgentDecisionStep.buildUserPrompt(sampleInput) shouldBe "Find recent papers on RAG"
    }

    "fold prior reasoning into the user prompt across iterations" in {
      val withPriors = sampleInput.copy(
        priorReasoning = List("Looked at arXiv.", "Cross-referenced ACL.")
      )
      val user = SigilAgentDecisionStep.buildUserPrompt(withPriors)
      user should include("Find recent papers on RAG")
      user should include("Looked at arXiv.")
      user should include("Cross-referenced ACL.")
      user should include("--- Continue ---")
    }
  }
}
