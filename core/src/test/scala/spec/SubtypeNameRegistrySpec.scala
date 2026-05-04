package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec

/**
 * Verifies the Sigil-level `eventSubtypeNames` / `deltaSubtypeNames` /
 * `noticeSubtypeNames` accessors return only the subtypes belonging to
 * each tier — the contract Sage's Dart codegen relies on to populate
 * spice's `durableSubtypes` knob (BUGS.md #14).
 *
 * Before the fix, `summon[RW[Event]].definition` (cast from
 * `RW[Signal]`) returned every Signal subtype regardless of tier, so
 * downstream consumers had no way to ask "which subtypes are durable
 * Events vs ephemeral Notices/Deltas?" without hardcoding the answer.
 */
class SubtypeNameRegistrySpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  "eventSubtypeNames / deltaSubtypeNames / noticeSubtypeNames" should {
    "return disjoint sets matching the framework's CoreSignals split" in {
      val events = TestSigil.eventSubtypeNames
      val deltas = TestSigil.deltaSubtypeNames
      val notices = TestSigil.noticeSubtypeNames

      events should contain allOf ("Message", "ToolInvoke", "ToolResults", "ModeChange", "TopicChange", "AgentState", "Stop")
      deltas should contain allOf ("MessageDelta", "ToolDelta", "StateDelta", "AgentStateDelta", "LocationDelta", "ImageDelta")
      notices should contain allOf (
        "RequestConversationList",
        "ConversationListSnapshot",
        "ConversationCreated",
        "ConversationDeleted",
        "SwitchConversation",
        "ConversationSnapshot"
      )

      events.intersect(deltas) shouldBe empty
      events.intersect(notices) shouldBe empty
      deltas.intersect(notices) shouldBe empty
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
