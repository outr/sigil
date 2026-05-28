package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{GenerationSettings, Instructions, Mode, ToolPolicy}
import sigil.role.GeneralistRole
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, FindCapabilityTool}

/**
 * Sigil #302 — `find_capability` must precede `change_mode` in the
 * tool-priority ordering so small-model position bias channels
 * confused agents into discovery (the framework's CORE ideology)
 * instead of mode-switching.
 *
 * Field evidence — Sage wire log 2026-05-28 10:33:53 → 10:34:04:
 * agent issued `change_mode("coding")` three times in a row with
 * `reason = "Already in coding mode…"` because change_mode sat at
 * priority 0 (first slot) and the action tools the agent needed
 * weren't in scope. Position bias on small models picked the
 * first-slot tool over `find_capability` (which would have
 * recovered the action tools).
 */
class ToolPriorityOrderingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val testMode: Mode = new Mode {
    override val name: String = "priority-test-mode"
    override val description: String = "test"
    override val tools: ToolPolicy = ToolPolicy.Standard
  }

  private val agent = DefaultAgentParticipant(
    id                = TestAgent,
    modelId           = sigil.db.Model.id("test", "priority-model"),
    toolNames         = List(ChangeModeTool.schema.name),
    instructions      = Instructions(),
    generationSettings = GenerationSettings(),
    tools             = ToolPolicy.Standard,
    roles             = List(GeneralistRole)
  )

  "Sigil.effectiveToolNames priority (sigil #302)" should {

    "place find_capability ahead of change_mode in the wire roster" in Task {
      val names = TestSigil.effectiveToolNames(agent, testMode, suggested = Nil)
      val findIdx  = names.indexOf(FindCapabilityTool.schema.name)
      val changeIdx = names.indexOf(ChangeModeTool.schema.name)
      findIdx should be >= 0
      changeIdx should be >= 0
      // Discovery is the framework's CORE ideology — every other tool
      // (change_mode included) is reachable through find_capability.
      // The first slot must belong to discovery.
      findIdx should be < changeIdx
    }

    "place find_capability at position 0 specifically" in Task {
      val names = TestSigil.effectiveToolNames(agent, testMode, suggested = Nil)
      names.headOption shouldBe Some(FindCapabilityTool.schema.name)
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
