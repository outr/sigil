package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.metals.StopMetalsTool

/**
 * `stop_metals`'s description must price its consequences. An
 * affordance described only by its mechanics gets invoked by small
 * models as a "clean state" ritual — observed: a spontaneous
 * stop→start restart immediately before a bulk sweep, cold-starting
 * the exact server the sweep's validation depended on. The
 * description is the surface every embedding app inherits, so the
 * warning must live here, self-contained.
 */
class StopMetalsDescriptionSpec extends AnyWordSpec with Matchers {

  "stop_metals description" should {

    "state the destructive consequences and the re-import cost" in {
      val d = new StopMetalsTool().description
      d should include("DESTRUCTIVE")
      d should include("full build re-import")
    }

    "forbid ritual refresh/restart/prepare invocations" in {
      val d = new StopMetalsTool().description
      d should include("do NOT call this to \"refresh\", \"restart\", or \"prepare\"")
      d should include("a running server is already the prepared state")
    }

    "restrict appropriateness to an explicit user request" in {
      val d = new StopMetalsTool().description
      d.replace('\n', ' ') should include("ONLY when the user explicitly asks")
    }
  }
}
