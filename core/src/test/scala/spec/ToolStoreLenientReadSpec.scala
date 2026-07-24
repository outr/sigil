package spec

import fabric.{obj, str}
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.Sigil
import sigil.tool.Tool
import sigil.tool.core.{CoreTools, RecordConsentTool, RespondTool}

/**
 * Sigil #380 — removing a registered tool orphans its row in the `tools`
 * store, and the `Tool` poly has no graceful fallback, so the typed
 * `listTools` read throws "Type not found" mid-stream and aborts the
 * whole catalog (same class as #374 for `ToolOutput`). Two parts:
 *
 *   1. `Sigil.decodeToolsLeniently` skips a row whose poly type is no
 *      longer registered rather than throwing — so removing ANY tool is
 *      never DB-corrupting on read.
 *   2. `RecordConsentTool` (the tool whose #378 removal triggered this)
 *      stays REGISTERED in `CoreTools.all` — it's still injected when a
 *      consent-gated tool is in scope, so its type must remain resolvable
 *      (`reconcileConsentTool` handles keeping it out of the per-turn
 *      roster). Registration is separate from advertisement.
 */
class ToolStoreLenientReadSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val toolRW = summon[RW[Tool]]

  "Sigil.decodeToolsLeniently (sigil #380)" should {

    "skip a tool row whose poly type is no longer registered, keeping the valid ones" in {
      val valid = toolRW.read(RespondTool)
      val orphan = obj("type" -> str("RemovedNoLongerRegisteredTool"), "name" -> obj("value" -> str("removed")))
      val decoded = Sigil.decodeToolsLeniently(List(valid, orphan))
      decoded.map(_.schema.name.value) should contain("respond")
      decoded.map(_.schema.name.value) should not contain "removed"
    }

    "survive an orphan row that the typed read would throw on (the bug)" in {
      val orphan = obj("type" -> str("RemovedNoLongerRegisteredTool"))
      a[Throwable] should be thrownBy orphan.as[Tool](using toolRW)
      Sigil.decodeToolsLeniently(List(orphan)) shouldBe empty
    }
  }

  "RecordConsentTool registration (sigil #380)" should {
    "stay registered/cataloged in CoreTools.all so its rows resolve (it's still injected)" in {
      CoreTools.all.map(_.schema.name.value) should contain(RecordConsentTool.schema.name.value)
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.shutdown.sync()
      succeed
    }
  }
}
