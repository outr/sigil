package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.{
  AnalysisWork, CodingWork, ConversationMode, Mode, WorkType
}

/** Coverage for sigil bug #17 — `Mode.workType` lets a mode override
  * the agent's declared work shape so provider routing follows the
  * mode's intent. */
class ModeWorkTypeSpec extends AnyWordSpec with Matchers {

  "Mode.workType" should {
    "default to None on the trait" in {
      object NoWorkTypeMode extends Mode {
        override val name: String = "no-work-type"
        override val description: String = "test"
      }
      NoWorkTypeMode.workType shouldBe None
    }

    "be None on ConversationMode (chat is the per-agent default)" in {
      ConversationMode.workType shouldBe None
    }

    "be Some(CodingWork) on WorkflowBuilderMode (workflow authoring is coding)" in {
      sigil.workflow.WorkflowBuilderMode.workType shouldBe Some(CodingWork)
    }

    "be overridable by a custom Mode" in {
      object MyAnalysisMode extends Mode {
        override val name: String = "analysis"
        override val description: String = "test"
        override val workType: Option[WorkType] = Some(AnalysisWork)
      }
      MyAnalysisMode.workType shouldBe Some(AnalysisWork)
    }
  }
}
