package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.behavior.Behavior
import sigil.participant.DefaultAgentParticipant
import sigil.provider.{ConversationMode, GenerationSettings, Instructions, Mode, ToolPolicy}
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, CoreTools, FindCapabilityTool, NoResponseTool, RespondTool, StopTool}

/**
 * Coverage for the [[Mode]] PolyType, [[ToolPolicy]] policy cases,
 * and the Sigil helpers that compose tools per turn
 * (`effectiveToolNames`, `discoveryCatalog`).
 */
class ModeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val essentials: Set[ToolName] =
    List(RespondTool, NoResponseTool, ChangeModeTool, StopTool).map(_.schema.name).toSet
  private val withDiscovery: Set[ToolName] =
    essentials + FindCapabilityTool.schema.name

  private val toolA = ToolName("tool_a")
  private val toolB = ToolName("tool_b")
  private val toolC = ToolName("tool_c")
  private val toolD = ToolName("tool_d")

  private def agent(tools: List[ToolName]): DefaultAgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = sigil.db.Model.id("test", "model"),
      toolNames = tools,
      instructions = Instructions(),
      generationSettings = GenerationSettings()
    )

  private def mode(modeTools: ToolPolicy): Mode = new Mode {
    override val name: String = "test-mode"
    override val description: String = "test"
    override val tools: ToolPolicy = modeTools
  }

  // No-op behavior for mode-only effectiveToolNames tests — `tools = ToolPolicy.Standard`
  // means it contributes nothing to the policy fold, so the result is governed solely
  // by the supplied mode policy.
  private val noopBehavior: Behavior = Behavior(name = "test-behavior", description = "")

  "Mode PolyType serialization" should {
    "round-trip ConversationMode via Mode's polymorphic RW" in Task {
      val rw = summon[RW[Mode]]
      val restored = rw.write(rw.read(ConversationMode))
      restored shouldBe ConversationMode
    }

    "round-trip a registered app mode via Mode's polymorphic RW" in Task {
      val rw = summon[RW[Mode]]
      val restored = rw.write(rw.read(TestCodingMode))
      restored shouldBe TestCodingMode
    }
  }

  "Sigil.modeByName" should {
    "resolve ConversationMode" in Task {
      TestSigil.modeByName("conversation") shouldBe Some(ConversationMode)
    }

    "resolve app-registered modes" in Task {
      TestSigil.modeByName("coding") shouldBe Some(TestCodingMode)
      TestSigil.modeByName("skilled") shouldBe Some(TestSkilledMode)
    }

    "return None for unknown names" in Task {
      TestSigil.modeByName("nope") shouldBe None
    }
  }

  "Sigil.effectiveToolNames" should {
    val a = agent(List(toolA, toolB))

    "leave baseline untouched under ToolPolicy.Standard" in Task {
      val result = TestSigil.effectiveToolNames(a, noopBehavior, mode(ToolPolicy.Standard), suggested = Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolA, toolB))
    }

    "suppress baseline AND find_capability under ToolPolicy.None" in Task {
      val result = TestSigil.effectiveToolNames(a, noopBehavior, mode(ToolPolicy.None), suggested = Nil).toSet
      result shouldBe essentials
      result should not contain FindCapabilityTool.schema.name
    }

    "add mode tools on top of baseline under ToolPolicy.Active" in Task {
      val result = TestSigil.effectiveToolNames(a, noopBehavior, mode(ToolPolicy.Active(List(toolC))), suggested = Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolA, toolB, toolC))
    }

    "not add mode tools to the roster under ToolPolicy.Discoverable" in Task {
      val result = TestSigil.effectiveToolNames(a, noopBehavior, mode(ToolPolicy.Discoverable(List(toolC))), suggested = Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolA, toolB))
    }

    "replace baseline with mode tools under ToolPolicy.Exclusive" in Task {
      val result = TestSigil.effectiveToolNames(a, noopBehavior, mode(ToolPolicy.Exclusive(List(toolC))), suggested = Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolC))
      result should not contain toolA
    }

    "leave baseline untouched under ToolPolicy.Scoped" in Task {
      val result = TestSigil.effectiveToolNames(a, noopBehavior, mode(ToolPolicy.Scoped(List(toolC))), suggested = Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolA, toolB))
    }

    "always include suggested tools (regardless of policy)" in Task {
      val suggested = List(toolD)
      val active = TestSigil.effectiveToolNames(a, noopBehavior, mode(ToolPolicy.Active(Nil)), suggested).toSet
      val excl   = TestSigil.effectiveToolNames(a, noopBehavior, mode(ToolPolicy.Exclusive(Nil)), suggested).toSet
      val none   = TestSigil.effectiveToolNames(a, noopBehavior, mode(ToolPolicy.None), suggested).toSet
      active should contain(toolD)
      excl should contain(toolD)
      none should contain(toolD)
    }
  }

  // Discovery filtering is exercised by DbToolFinderSpec, which covers
  // the same matrix as the prior `modeAllowsDiscovery` tests but against
  // the live finder + DiscoveryFilter helper.
}
