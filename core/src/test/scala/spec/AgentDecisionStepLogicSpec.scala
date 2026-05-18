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
    id = "decision-0",
    role = Role(name = "researcher", description = "Research and synthesize.", workType = AnalysisWork),
    brief = "Find recent papers on RAG",
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

  "extractParentAnswers" should {
    import fabric.{obj, str}
    import lightdb.id.Id
    import strider.Workflow
    import strider.step.Step
    import sigil.workflow.{ParentAnswer, SigilAgentDecisionStep}

    /**
     * Minimal Workflow base — everything extractParentAnswers reads
     * is overridden in the per-test `copy`.
     */
    val baseWorkflow: Workflow = Workflow(
      name = "test",
      steps = Nil,
      scheduled = 0L,
      queue = Nil,
      sourceId = Id("source")
    )

    /**
     * Build a Workflow with the given trigger payloads (questionId →
     * answer pairs). The corresponding step ids are placed in
     * `steps` in payload-iteration order so ordering is
     * deterministic.
     */
    def workflowWith(pairs: (String, String)*): Workflow = {
      val triggerSteps: List[Step] = pairs.toList.map { case (qid, _) =>
        new strider.step.Trigger {
          override val id: Id[Step] = Id[Step](s"trig-$qid")
          override val name: String = s"trig-$qid"
          override def register(w: Workflow) = rapid.Task.pure(fabric.Null)
          override def check(w: Workflow) = rapid.Task.pure(None)
        }
      }
      val payloads: Map[Id[Step], fabric.Json] =
        triggerSteps.zip(pairs).map { case (step, (qid, answer)) =>
          step.id -> obj("taskId" -> str("t"), "questionId" -> str(qid), "answer" -> str(answer))
        }.toMap
      baseWorkflow.copy(steps = triggerSteps, payloads = payloads)
    }

    "read every answer from workflow.payloads (the Strider field that actually carries trigger settle output)" in {
      val wf = workflowWith("q1" -> "blue", "q2" -> "small")
      val answers = SigilAgentDecisionStep.extractParentAnswers(wf)
      answers.map(_.questionId) should contain theSameElementsAs List("q1", "q2")
      answers.map(_.answer) should contain theSameElementsAs List("blue", "small")
    }

    "return them oldest-to-newest by their trigger step's position in workflow.steps" in {
      val wf = workflowWith("q1" -> "blue", "q2" -> "small", "q3" -> "third")
      val answers = SigilAgentDecisionStep.extractParentAnswers(wf)
      answers.map(_.questionId) shouldBe List("q1", "q2", "q3")
    }

    "ignore stepResults entries — they never carry trigger payloads" in {
      // Regression for the silent-drop bug: even with a stepResult
      // that looks shaped like an answer, the extractor's source of
      // truth is workflow.payloads, not workflow.stepResults.
      val wf = baseWorkflow.copy(
        stepResults = List(strider.StepResult(
          stepId = Id[Step]("not-a-trigger"),
          stepName = "fake",
          status = strider.StepResultStatus.Completed,
          output = Some(obj("questionId" -> str("ignored"), "answer" -> str("ignored"))),
          durationMs = 0L
        ))
      )
      SigilAgentDecisionStep.extractParentAnswers(wf) shouldBe empty
    }

    "return an empty list when there are no trigger payloads" in {
      SigilAgentDecisionStep.extractParentAnswers(baseWorkflow) shouldBe empty
    }
  }
}
