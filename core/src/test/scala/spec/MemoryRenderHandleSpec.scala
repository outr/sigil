package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextMemory, MemorySource}
import sigil.provider.ContextSections

/**
 * The rendered memory line and its `lookup` drill-down handle: the
 * summary is the injected form, and whenever it elides materially
 * more fact text than it shows, the line carries a reference the
 * model can follow — the memory's key, or its record id when keyless
 * (`lookup` resolves both).
 */
class MemoryRenderHandleSpec extends AnyWordSpec with Matchers {

  private val longFact =
    "He keeps his tobacco in the toe end of a Persian slipper upon the mantelpiece, " +
      "his cigars in the coal-scuttle, and his unanswered correspondence transfixed by " +
      "a jack-knife into the very centre of his wooden mantelpiece."

  private def memory(fact: String, summary: String, key: Option[String]): ContextMemory =
    ContextMemory(
      fact = fact,
      label = "render",
      summary = summary,
      source = MemorySource.Explicit,
      spaceId = TestSpace,
      key = key,
      _id = ContextMemory.id("render-fixture")
    )

  "memoryRenderText" should {
    "render the fact when no summary is set" in {
      ContextSections.memoryRenderText(memory(longFact, "", None)) shouldBe longFact
    }

    "render the summary without a handle when it IS the fact" in {
      val m = memory("Prefers dark roast coffee.", "Prefers dark roast coffee.", Some("user.coffee"))
      ContextSections.memoryRenderText(m) shouldBe "Prefers dark roast coffee."
    }

    "carry a keyed handle when the summary elides a materially longer fact" in {
      val m = memory(longFact, "Keeps tobacco in a Persian slipper.", Some("holmes.tobacco"))
      ContextSections.memoryRenderText(m) shouldBe
        "Keeps tobacco in a Persian slipper. [full: lookup(\"holmes.tobacco\")]"
    }

    "fall back to the record id for a keyless elided memory" in {
      val m = memory(longFact, "Keeps tobacco in a Persian slipper.", None)
      ContextSections.memoryRenderText(m) shouldBe
        "Keeps tobacco in a Persian slipper. [full: lookup(\"render-fixture\")]"
    }

    "skip the handle when the fact is only marginally longer than the summary" in {
      val m = memory("Prefers dark roast coffee, ideally Colombian.", "Prefers dark roast coffee.", Some("user.coffee"))
      ContextSections.memoryRenderText(m) shouldBe "Prefers dark roast coffee."
    }
  }
}
