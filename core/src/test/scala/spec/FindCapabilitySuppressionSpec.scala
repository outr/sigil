package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.db.Model
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{ConversationMode, GenerationSettings, Instructions, ToolPolicy}
import sigil.tool.ToolName
import sigil.tool.core.{CoreTools, FindCapabilityTool, RespondTool}

/**
 * Sigil #388 — `ToolPolicy.ActiveOnly` / `ToolPolicy.None` set
 * `includesFindCapability = false`, which must mean "ensure
 * find_capability is ABSENT", not merely "don't add it". It used to leak
 * back in via baseline (`agent.toolNames`), the policy's own `names`, or
 * `suggested` — e.g. a roster built from `CoreTools.coreToolNames` (which
 * includes find_capability). The fix is a final filter in
 * `effectiveToolNames`.
 */
class FindCapabilitySuppressionSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "model")
  private val fc = FindCapabilityTool.name
  private val respond = RespondTool.name

  private def agentWith(toolNames: List[ToolName], policy: ToolPolicy): DefaultAgentParticipant =
    DefaultAgentParticipant(
      id                 = TestAgent,
      modelId            = modelId,
      toolNames          = toolNames,
      instructions       = Instructions(),
      generationSettings = GenerationSettings(),
      tools              = policy
    )

  // A roster that inadvertently contains find_capability (the natural case:
  // built from CoreTools.coreToolNames, which includes it).
  private val rosterWithFc: List[ToolName] = (CoreTools.coreToolNames :+ fc).distinct

  "effectiveToolNames (sigil #388)" should {

    "strip find_capability under ActiveOnly even when it's in agent.toolNames" in {
      val names = TestSigil.effectiveToolNames(
        agentWith(rosterWithFc, ToolPolicy.ActiveOnly(rosterWithFc)), ConversationMode, suggested = Nil)
      names should not contain fc
    }

    "strip find_capability under ActiveOnly even when it's in the policy's names" in {
      // toolNames clean, but the policy names list carries find_capability.
      val names = TestSigil.effectiveToolNames(
        agentWith(List(respond), ToolPolicy.ActiveOnly(List(respond, fc))), ConversationMode, suggested = Nil)
      names should not contain fc
    }

    "strip find_capability under ActiveOnly even when it arrives via suggested" in {
      val names = TestSigil.effectiveToolNames(
        agentWith(List(respond), ToolPolicy.ActiveOnly(List(respond))), ConversationMode, suggested = List(fc))
      names should not contain fc
    }

    "strip find_capability under ToolPolicy.None even when listed" in {
      val names = TestSigil.effectiveToolNames(
        agentWith(rosterWithFc, ToolPolicy.None), ConversationMode, suggested = List(fc))
      names should not contain fc
    }

    "still INCLUDE find_capability under the default Standard policy" in {
      val names = TestSigil.effectiveToolNames(
        agentWith(List(respond), ToolPolicy.Standard), ConversationMode, suggested = Nil)
      names should contain(fc)
    }

    "leave the rest of the roster intact when stripping find_capability" in {
      val names = TestSigil.effectiveToolNames(
        agentWith(rosterWithFc, ToolPolicy.ActiveOnly(rosterWithFc)), ConversationMode, suggested = Nil)
      names should contain(respond) // a real tool the agent asked for survives
    }
  }
}
