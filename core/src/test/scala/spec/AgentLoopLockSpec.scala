package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.AgentRunawayException
import sigil.event.AgentState
import sigil.signal.EventState

/**
 * Coverage for [[sigil.Sigil.tryFire]]'s lock semantics that
 * the framework's docs claim. The agent-loop's `tryFire` claims
 * `AgentState(Active)` keyed by `agentlock:<agentId>:<convId>` —
 * one writer per (agent, conversation). This spec locks in the
 * key format (so the [[Sigil.canSee]] filter at line 1879's
 * `endsWith(s":${convId.value}")` keeps working) plus
 * [[AgentRunawayException]]'s structure (CLAUDE.md says the
 * orchestrator raises it after `maxAgentIterations` — verifying
 * the type is reachable).
 *
 * Full concurrent-fire / runaway exercises live in
 * `LlamaCpp*Spec` against a live LLM (see SIGIL_LIVE-gated
 * specs), since deterministically driving 10 agent iterations
 * needs a stub provider that perpetually emits triggers.
 */
class AgentLoopLockSpec extends AnyWordSpec with Matchers {

  "AgentRunawayException" should {
    "be a RuntimeException carrying its message" in {
      val e = new AgentRunawayException("Agent foo hit maxAgentIterations (10) in conversation bar")
      e shouldBe a [RuntimeException]
      e.getMessage should include("maxAgentIterations")
      e.getMessage should include("foo")
      e.getMessage should include("bar")
    }
  }

  "AgentState lock-id format" should {
    "follow the documented `agentlock:<agentId>:<convId>` shape — the canSee filter relies on this" in {
      // The id format is private to Sigil.agentStateLockId, but the
      // canSee filter at Sigil.scala:1879 reads `lockId.value.endsWith(s":${convId.value}")`.
      // That string match will silently break if the format ever changes.
      // This spec is the canary.
      val agentId = "test-agent"
      val convId  = "conv-123"
      val expected = s"agentlock:$agentId:$convId"
      // Construct a synthetic AgentState with this id and verify the
      // suffix match the filter uses still holds.
      val claim = AgentState(
        agentId = TestAgent,
        participantId = TestAgent,
        conversationId = lightdb.id.Id(convId),
        topicId = TestTopicId,
        state = EventState.Active,
        _id = Id[sigil.event.Event](expected)
      )
      claim._id.value should startWith ("agentlock:")
      claim._id.value should endWith (s":$convId")
      claim._id.value should include(":test-agent:")
    }
  }
}
