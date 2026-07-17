package spec

import fabric.*
import fabric.io.JsonParser
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.event.Message
import sigil.signal.EventState
import sigil.upgrade.ContextFrameToolResultMigrationUpgrade

/**
 * Regression for sigil #294 — `ContextFrame.ToolResult` was removed
 * in #265 without a migration. Boot reads from pre-#265 databases
 * tripped on the dead discriminator and aborted.
 *
 * Covers the pure-JSON rewrite path:
 *   - Orphan rows (whose `contextFrame.type == "ToolResult"`) detect
 *     correctly.
 *   - The rewrite nulls `contextFrame` and produces a typed
 *     [[Message]] that decodes cleanly.
 *   - Non-orphan rows pass through unchanged (returns `None` so the
 *     upgrade walks past them without unnecessary upserts).
 *
 * Registration on the framework's upgrade chain is covered indirectly
 * by every spec that boots TestSigil — `ContextFrameToolResultMigrationUpgrade`
 * is wired in front of the static-tool / static-skill upgrades, so a
 * clean boot proves the migration runs and does no harm.
 */
class ContextFrameToolResultMigrationSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  // The pre-#265 JSON shape — the rescue migration's input. Anchored
  // on a real `Message` event whose `contextFrame` carries the
  // retired `ToolResult` discriminator.
  private def orphanRow(eventId: String, callId: String, content: String): Json = {
    val baseMessage = Message(
      participantId = TestUser,
      conversationId = lightdb.id.Id(s"conv-$eventId"),
      topicId = TestTopicId,
      state = EventState.Complete,
      _id = lightdb.id.Id(eventId)
    )
    val baseJson = summon[RW[sigil.event.Event]].read(baseMessage)
    // Splice in the legacy `contextFrame: ToolResult(...)` shape that
    // the current ContextFrame enum no longer accepts.
    val legacyContextFrame = obj(
      "type" -> str("ToolResult"),
      "callId" -> str(callId),
      "content" -> str(content),
      "sourceEventId" -> str(eventId),
      "visibility" -> obj("type" -> str("All"))
    )
    baseJson.merge(obj("contextFrame" -> legacyContextFrame))
  }

  "ContextFrameToolResultMigrationUpgrade (sigil #294)" should {

    "detect rows whose contextFrame carries the retired ToolResult discriminator" in {
      val orphan = orphanRow("evt-1", "call-1", "tool output text")
      ContextFrameToolResultMigrationUpgrade.isOrphanToolResult(orphan) shouldBe true
    }

    "leave non-orphan rows alone" in {
      val cleanMessage = Message(
        participantId = TestUser,
        conversationId = lightdb.id.Id("conv-clean"),
        topicId = TestTopicId,
        state = EventState.Complete
      )
      val cleanJson = summon[RW[sigil.event.Event]].read(cleanMessage)
      ContextFrameToolResultMigrationUpgrade.isOrphanToolResult(cleanJson) shouldBe false
      ContextFrameToolResultMigrationUpgrade.rewriteOrphanRow(cleanJson) shouldBe None
    }

    "rewrite the orphan row into a typed Message with contextFrame = null" in {
      val orphan = orphanRow("evt-2", "call-2", "rewritten output")
      val rewritten = ContextFrameToolResultMigrationUpgrade.rewriteOrphanRow(orphan)
      rewritten shouldBe defined
      val event = rewritten.get
      event shouldBe a[Message]
      val asMessage = event.asInstanceOf[Message]
      asMessage.contextFrame shouldBe None
      asMessage._id.value shouldBe "evt-2"
      // The Message's durable fields survive — participant, conversation,
      // topic, state — only the cached `contextFrame` projection drops.
      asMessage.participantId shouldBe TestUser
      asMessage.state shouldBe EventState.Complete
    }

    "extract the row's _id for diagnostic logging" in {
      val orphan = orphanRow("evt-3", "call-3", "")
      ContextFrameToolResultMigrationUpgrade.extractOrphanId(orphan) shouldBe Some("evt-3")
    }

    "return None on a row with no _id" in {
      val noId = obj("contextFrame" -> obj("type" -> str("ToolResult")))
      ContextFrameToolResultMigrationUpgrade.extractOrphanId(noId) shouldBe None
    }

    // ---- Sigil #295 — dead-outer Event-poly rows ----

    "detect rows whose top-level `type` is a retired Event discriminator" in {
      val deadToolResults = obj(
        "type" -> str("ToolResults"),
        "_id" -> str("evt-tr-1"),
        "outcome" -> obj("type" -> str("Success"))
      )
      val deadToolCall = obj(
        "type" -> str("ToolCall"),
        "_id" -> str("evt-tc-1")
      )
      val liveMessage = obj("type" -> str("Message"), "_id" -> str("evt-msg-1"))
      ContextFrameToolResultMigrationUpgrade.isDeadOuterEvent(deadToolResults) shouldBe true
      ContextFrameToolResultMigrationUpgrade.isDeadOuterEvent(deadToolCall) shouldBe true
      ContextFrameToolResultMigrationUpgrade.isDeadOuterEvent(liveMessage) shouldBe false
    }

    "classify a mixed batch into drops + rewrites" in {
      val deadOuter = obj(
        "type" -> str("ToolResults"),
        "_id" -> str("evt-tr-classify"),
        "origin" -> str("evt-paired-1"),
        "outcome" -> obj("type" -> str("Success"))
      )
      val deadInnerOnly = orphanRow("evt-inner-classify", "call-inner", "inner-content")
      val deadBoth = obj(
        "type" -> str("ToolCall"),
        "_id" -> str("evt-both-classify"),
        "contextFrame" -> obj("type" -> str("ToolResult"))
      )
      val clean = summon[RW[sigil.event.Event]].read(Message(
        participantId = TestUser,
        conversationId = lightdb.id.Id("conv-clean-classify"),
        topicId = TestTopicId,
        state = EventState.Complete
      ))
      val (drops, rewrites) = ContextFrameToolResultMigrationUpgrade.classifyRows(
        List(deadOuter, deadInnerOnly, deadBoth, clean)
      )
      // dead-outer rows AND dead-both rows drop; clean and inner-only stay.
      drops should contain("evt-tr-classify")
      drops should contain("evt-both-classify")
      drops should not contain "evt-inner-classify"
      // Only the inner-only orphan produces a rewrite.
      rewrites.map(_._id.value) shouldBe List("evt-inner-classify")
    }

    "drop wins over rewrite when a row is both dead-outer AND dead-inner" in {
      val deadBoth = obj(
        "type" -> str("ToolResults"),
        "_id" -> str("evt-both-drop"),
        "outcome" -> obj("type" -> str("Success")),
        "contextFrame" -> obj("type" -> str("ToolResult"))
      )
      val (drops, rewrites) = ContextFrameToolResultMigrationUpgrade.classifyRows(List(deadBoth))
      drops shouldBe List("evt-both-drop")
      rewrites shouldBe empty
    }

    "log + skip dead-outer rows that don't carry an _id" in {
      val noId = obj("type" -> str("ToolResults"))
      val (drops, rewrites) = ContextFrameToolResultMigrationUpgrade.classifyRows(List(noId))
      drops shouldBe empty
      rewrites shouldBe empty
    }
  }

  "tear down" should {
    "dispose TestSigil" in {
      TestSigil.shutdown.sync()
      succeed
    }
  }
}
