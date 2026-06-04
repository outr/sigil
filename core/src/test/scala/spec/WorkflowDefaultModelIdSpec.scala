package spec

import fabric.{Json, Null, str}
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.workflow.SigilWorkflowVariables
import strider.{Workflow, WorkflowParent}

/**
 * Coverage for sigil bug #65 — workflow-level default model id
 * lets workflow authors pin once at creation rather than
 * threading the modelId through every step's input.
 *
 * Sourced from `Workflow.variables` (existing Strider surface)
 * under the reserved `SigilWorkflowVariables.DefaultModelId`
 * key. Per-step `modelId` (when set) wins; the default is the
 * fallback when a step leaves its modelId empty.
 */
class WorkflowDefaultModelIdSpec extends AnyWordSpec with Matchers {

  private def workflow(vars: Map[String, Json]): Workflow =
    Workflow(
      name = "default-model-test",
      steps = Nil,
      scheduled = 0L,
      queue = Nil,
      sourceId = Id[WorkflowParent]("source"),
      variables = vars
    )

  "SigilWorkflowVariables.defaultModelIdOf" should {

    "return None when the variable is unset" in {
      SigilWorkflowVariables.defaultModelIdOf(workflow(Map.empty)) shouldBe None
    }

    "return Some(value) when the variable is a non-empty string" in {
      val wf = workflow(Map(SigilWorkflowVariables.DefaultModelId -> str("openai/gpt-5-haiku")))
      SigilWorkflowVariables.defaultModelIdOf(wf) shouldBe Some("openai/gpt-5-haiku")
    }

    "trim whitespace and treat empty strings as None" in {
      val whitespaceOnly = workflow(Map(SigilWorkflowVariables.DefaultModelId -> str("   ")))
      SigilWorkflowVariables.defaultModelIdOf(whitespaceOnly) shouldBe None
    }

    "return None when the variable is JSON null" in {
      val nullValue = workflow(Map(SigilWorkflowVariables.DefaultModelId -> Null))
      SigilWorkflowVariables.defaultModelIdOf(nullValue) shouldBe None
    }

    "use the framework-reserved `__sigil_` prefix to avoid colliding with app variables" in {
      SigilWorkflowVariables.DefaultModelId should startWith("__sigil_")
    }
  }
}
