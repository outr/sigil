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

  "parseAskParent" should {
    "extract a question following an `AskParent:` line marker" in {
      val response = "Reasoning so far.\nAskParent: Should we use OAuth or basic auth?"
      SigilAgentDecisionStep.parseAskParent(response) shouldBe Some("Should we use OAuth or basic auth?")
    }

    "be case-insensitive" in {
      SigilAgentDecisionStep.parseAskParent("askParent: clarify scope?") shouldBe Some("clarify scope?")
    }

    "ignore mid-paragraph mentions" in {
      SigilAgentDecisionStep.parseAskParent("the agent should AskParent: in cases like this") shouldBe None
    }
  }

  "parseMarker" should {
    "prefer Complete over AskParent if both are present (terminating wins over waiting)" in {
      val response = "AskParent: anything?\nComplete: done."
      SigilAgentDecisionStep.parseMarker(response) shouldBe SigilAgentDecisionStep.MarkerCompletion("done.")
    }

    "return MarkerAskParent when only AskParent is present" in {
      val response = "Some thinking.\nAskParent: which DB?"
      SigilAgentDecisionStep.parseMarker(response) shouldBe SigilAgentDecisionStep.MarkerAskParent("which DB?")
    }

    "return MarkerReport when Report is present (and no higher-priority markers)" in {
      val response = "Working on it.\nReport: I've finished phase 1; moving to phase 2."
      SigilAgentDecisionStep.parseMarker(response) shouldBe
        SigilAgentDecisionStep.MarkerReport("I've finished phase 1; moving to phase 2.")
    }

    "return MarkerStatus when Status is present (and no higher-priority markers)" in {
      val response = "Reasoning.\nStatus: compiling step 3/7"
      SigilAgentDecisionStep.parseMarker(response) shouldBe
        SigilAgentDecisionStep.MarkerStatus("compiling step 3/7")
    }

    "prefer AskParent over Report when both are present (waiting beats reporting)" in {
      val response = "Report: done with research.\nAskParent: which option?"
      SigilAgentDecisionStep.parseMarker(response) shouldBe SigilAgentDecisionStep.MarkerAskParent("which option?")
    }

    "prefer Report over Status when both are present" in {
      val response = "Status: in progress\nReport: milestone reached"
      SigilAgentDecisionStep.parseMarker(response) shouldBe SigilAgentDecisionStep.MarkerReport("milestone reached")
    }

    "return MarkerNone when no markers are present" in {
      SigilAgentDecisionStep.parseMarker("Just thinking out loud.") shouldBe SigilAgentDecisionStep.MarkerNone
    }
  }
}
