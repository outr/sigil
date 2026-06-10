package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.event.{Event, Message, ToolInvoke}
import sigil.signal.EventState
import sigil.tool.{TextToolOutput, ToolName, ToolOutput, UnknownToolOutput}
import sigil.upgrade.ToolOutputReconcileUpgrade

/**
 * Regression for sigil #374 — renaming/removing a `ToolOutput` subtype
 * left every `ToolInvoke.output` row referencing it undecodable, and
 * because the events store is read typed, a single such row aborted
 * boot for the whole database. Real trigger: `BrowserScreenshotOutput`
 * → `ImageToolOutput` (commit b1ed27c1).
 *
 * Covers the pure-JSON detect + rewrite path:
 *   - A row whose `output` discriminator is unregistered fails the
 *     typed `Event` read (the boot-bricking symptom) and is detected as
 *     an orphan.
 *   - The rewrite swaps the orphaned block for [[UnknownToolOutput]],
 *     preserving the original block verbatim in `raw`, and produces a
 *     typed [[ToolInvoke]] that decodes — and re-serializes — cleanly.
 *   - A valid output, and any non-`ToolInvoke` row, pass through
 *     untouched (`repairedEvent` returns `None`).
 *
 * Registration on the framework's upgrade chain is covered indirectly
 * by every spec that boots TestSigil — `ToolOutputReconcileUpgrade` is
 * wired in front of the static-tool / static-skill upgrades, so a clean
 * boot proves it runs and does no harm.
 */
class ToolOutputReconcileUpgradeSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val eventRW: RW[Event] = summon[RW[Event]]

  /** A valid, fully-serialized `ToolInvoke` event to anchor the splice. */
  private def baseInvokeJson(id: String, output: ToolOutput): Json =
    eventRW.read(ToolInvoke(
      toolName       = ToolName("browser_screenshot"),
      participantId  = TestUser,
      conversationId = Id(s"conv-$id"),
      topicId        = TestTopicId,
      output         = output,
      state          = EventState.Complete,
      _id            = Id(id)
    ))

  /** The pre-rename on-disk shape — a `ToolInvoke` whose `output` block
    * carries the retired `BrowserScreenshotOutput` discriminator the
    * current `ToolOutput` poly no longer knows. */
  private val legacyOutputBlock: Json = obj(
    "type"    -> str("BrowserScreenshotOutput"),
    "fileId"  -> str("file-1"),
    "url"     -> str("https://example.test/shot.png"),
    "altText" -> str("a screenshot")
  )

  private def orphanRow(id: String): Json = {
    val base = baseInvokeJson(id, TextToolOutput("placeholder"))
    Obj(base.asMap.updated("output", legacyOutputBlock))
  }

  "ToolOutputReconcileUpgrade (sigil #374)" should {

    "fail the typed Event read on an unregistered output discriminator (the boot-bricking symptom)" in {
      a[Throwable] should be thrownBy orphanRow("evt-red").as[Event](using eventRW)
    }

    "detect a ToolInvoke row whose output block is no longer decodable" in {
      val orphan = orphanRow("evt-1")
      ToolOutputReconcileUpgrade.isToolInvoke(orphan) shouldBe true
      ToolOutputReconcileUpgrade.outputIsOrphan(orphan) shouldBe true
    }

    "leave a ToolInvoke with a valid output untouched" in {
      val good = baseInvokeJson("evt-good", TextToolOutput("ok"))
      ToolOutputReconcileUpgrade.isToolInvoke(good) shouldBe true
      ToolOutputReconcileUpgrade.outputIsOrphan(good) shouldBe false
      ToolOutputReconcileUpgrade.repairedEvent(good) shouldBe None
    }

    "ignore non-ToolInvoke rows" in {
      val message = eventRW.read(Message(
        participantId  = TestUser,
        conversationId = Id("conv-msg"),
        topicId        = TestTopicId,
        state          = EventState.Complete
      ))
      ToolOutputReconcileUpgrade.isToolInvoke(message) shouldBe false
      ToolOutputReconcileUpgrade.repairedEvent(message) shouldBe None
    }

    "rewrite the orphan into a typed ToolInvoke whose output is a lossless UnknownToolOutput" in {
      val repaired = ToolOutputReconcileUpgrade.repairedEvent(orphanRow("evt-2"))
      repaired shouldBe defined
      val event = repaired.get
      event shouldBe a[ToolInvoke]
      val invoke = event.asInstanceOf[ToolInvoke]
      // Durable fields survive.
      invoke._id.value shouldBe "evt-2"
      invoke.toolName shouldBe ToolName("browser_screenshot")
      invoke.state shouldBe EventState.Complete
      // The orphaned output is preserved losslessly.
      invoke.output shouldBe a[UnknownToolOutput]
      val unknown = invoke.output.asInstanceOf[UnknownToolOutput]
      unknown.typeTag shouldBe "BrowserScreenshotOutput"
      unknown.raw shouldBe legacyOutputBlock
    }

    "produce a row that re-serializes and re-decodes cleanly (boot survives going forward)" in {
      val repaired = ToolOutputReconcileUpgrade.repairedEvent(orphanRow("evt-3")).get
      val roundTripped = eventRW.read(repaired)
      noException should be thrownBy roundTripped.as[Event](using eventRW)
    }

    "extract the row's _id for diagnostic logging" in {
      ToolOutputReconcileUpgrade.extractId(orphanRow("evt-4")) shouldBe Some("evt-4")
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.shutdown.sync()
      succeed
    }
  }
}
