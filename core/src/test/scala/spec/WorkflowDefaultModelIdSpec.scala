package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import strider.{Workflow, WorkflowParent}
import strider.step.Step

/**
 * Coverage for sigil bug #65 — `Workflow.defaultModelId` lets
 * workflow authors pin the model once at workflow creation
 * rather than threading it through every step's input. Here we
 * exercise the framework-level field directly; per-step
 * fallback resolution is tested implicitly by the
 * `WorkflowEndToEndSpec` / `LlamaCppWorkerSpec` paths that
 * already drive workflows end-to-end.
 */
class WorkflowDefaultModelIdSpec extends AnyWordSpec with Matchers {

  "Workflow.defaultModelId" should {

    "default to None on a freshly-constructed Workflow" in {
      val wf = Workflow(
        name      = "no-default-model",
        steps     = Nil,
        scheduled = 0L,
        queue     = Nil,
        sourceId  = Id[WorkflowParent]("source")
      )
      wf.defaultModelId shouldBe None
    }

    "round-trip an explicit value through copy" in {
      val wf = Workflow(
        name           = "with-default-model",
        steps          = Nil,
        scheduled      = 0L,
        queue          = Nil,
        sourceId       = Id[WorkflowParent]("source"),
        defaultModelId = Some("openai/gpt-5-haiku")
      )
      wf.defaultModelId shouldBe Some("openai/gpt-5-haiku")
      wf.copy(name = "renamed").defaultModelId shouldBe Some("openai/gpt-5-haiku")
    }
  }
}
