package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.provider.CodingWork
import sigil.script.ScriptAuthoringMode

class ScriptAuthoringModeWorkTypeSpec extends AnyWordSpec with Matchers {
  "ScriptAuthoringMode" should {
    "pin workType to CodingWork (sigil bug #17)" in {
      ScriptAuthoringMode.workType shouldBe Some(CodingWork)
    }
  }
}
