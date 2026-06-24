package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.browser.WebBrowserMode
import sigil.provider.AnalysisWork

class WebBrowserModeWorkTypeSpec extends AnyWordSpec with Matchers {
  "WebBrowserMode" should {
    "pin workType to AnalysisWork" in {
      WebBrowserMode.workType shouldBe Some(AnalysisWork)
    }
  }
}
