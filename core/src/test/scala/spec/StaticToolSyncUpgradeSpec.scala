package spec

import fabric.io.JsonParser
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.StaticToolSyncUpgrade

/**
 * Unit coverage for the polytype-error-tolerant prune path.
 * Integration coverage of the full sync flow already lives in
 * `AllShippedToolsSpec`; this spec targets the raw-JSON id
 * extraction the prune phase falls back to when a row's
 * polytype discriminator names a subtype that's no longer
 * registered (e.g. a stale `CancelTool` row left in the store
 * after the class was dropped in a later release).
 *
 * Reproducing the actual polytype error in-process isn't
 * tractable — fabric's `PolyType` registry is process-global
 * with no deregistration — so the spec verifies the helper
 * that recovers the orphan id from the raw JSON, which is the
 * mechanical piece the prune path leans on.
 */
class StaticToolSyncUpgradeSpec extends AnyWordSpec with Matchers {

  "StaticToolSyncUpgrade.extractOrphanId" should {

    "return the explicit _id when the lightdb row carries one" in {
      val json = JsonParser(
        """{"_id":"cancel","type":"CancelTool","name":{"value":"cancel"}}"""
      )
      StaticToolSyncUpgrade.extractOrphanId(json) shouldBe Some("cancel")
    }

    "fall back to name.value when _id is absent" in {
      val json = JsonParser(
        """{"type":"CancelTool","name":{"value":"cancel"}}"""
      )
      StaticToolSyncUpgrade.extractOrphanId(json) shouldBe Some("cancel")
    }

    "prefer _id over name.value when both are present and differ" in {
      val json = JsonParser(
        """{"_id":"legacy_id","type":"CancelTool","name":{"value":"cancel"}}"""
      )
      StaticToolSyncUpgrade.extractOrphanId(json) shouldBe Some("legacy_id")
    }

    "return None when neither _id nor name.value is present" in {
      val json = JsonParser("""{"type":"CancelTool"}""")
      StaticToolSyncUpgrade.extractOrphanId(json) shouldBe None
    }
  }
}
