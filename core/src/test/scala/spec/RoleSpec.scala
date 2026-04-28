package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.role.{GeneralistRole, Role}
import sigil.conversation.SkillSource
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{GenerationSettings, Instructions, Mode, ToolPolicy}
import sigil.tool.ToolName
import sigil.tool.core.{
  ChangeModeTool, FindCapabilityTool, NoResponseTool, RespondTool,
  RespondFailureTool, RespondFieldTool, RespondOptionsTool, StopTool
}

/**
 * Coverage for the [[Role]] role primitive, the
 * [[AgentParticipant.roles]] field's invariants, and the
 * agent + Mode policy fold inside `Sigil.effectiveToolNames`.
 *
 * Under the merged-dispatch model, tools are agent-level (one
 * `ToolPolicy` per `AgentParticipant`), so the fold here exercises
 * `agent.tools` against `mode.tools` rather than per-role policies.
 */
class RoleSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val essentials: Set[ToolName] =
    List(
      RespondTool, RespondOptionsTool, RespondFieldTool, RespondFailureTool,
      NoResponseTool, ChangeModeTool, StopTool
    ).map(_.schema.name).toSet
  private val withDiscovery: Set[ToolName] =
    essentials + FindCapabilityTool.schema.name

  private val toolA = ToolName("tool_a")
  private val toolB = ToolName("tool_b")
  private val toolC = ToolName("tool_c")
  private val toolD = ToolName("tool_d")

  private def agent(roles: List[Role] = List(GeneralistRole),
                    toolNames: List[ToolName] = Nil,
                    tools: ToolPolicy = ToolPolicy.Standard): DefaultAgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = sigil.db.Model.id("test", "model"),
      toolNames = toolNames,
      instructions = Instructions(),
      generationSettings = GenerationSettings(),
      tools = tools,
      roles = roles
    )

  private val standardMode: Mode = new Mode {
    override val name: String = "test-mode"
    override val description: String = "test"
    override val tools: ToolPolicy = ToolPolicy.Standard
  }

  "AgentParticipant.roles" should {
    "default to List(GeneralistRole)" in Task {
      val a: AgentParticipant = DefaultAgentParticipant(
        id = TestAgent,
        modelId = sigil.db.Model.id("test", "model")
      )
      a.roles shouldBe List(GeneralistRole)
    }

    "reject an empty roles list at construction" in Task {
      an[IllegalArgumentException] should be thrownBy {
        DefaultAgentParticipant(
          id = TestAgent,
          modelId = sigil.db.Model.id("test", "model"),
          roles = Nil
        )
      }
    }

    "accept a multi-role list" in Task {
      val planner = Role(name = "planner", description = "Plan steps.")
      val critic  = Role(name = "critic",  description = "Question assumptions.")
      val a = agent(roles = List(planner, critic))
      a.roles shouldBe List(planner, critic)
      a.roles.map(_.name) shouldBe List("planner", "critic")
    }
  }

  "GeneralistRole" should {
    "have a non-empty description" in Task {
      GeneralistRole.description.nonEmpty shouldBe true
    }

    "have name 'generalist'" in Task {
      GeneralistRole.name shouldBe "generalist"
    }
  }

  "Sigil.effectiveToolNames agent+mode fold" should {
    "behave like Mode-only when agent.tools is Standard" in Task {
      val a = agent(toolNames = List(toolA, toolB), tools = ToolPolicy.Standard)
      val mode: Mode = new Mode {
        val name = "m"; val description = "m"
        override val tools = ToolPolicy.Active(List(toolC))
      }
      val result = TestSigil.effectiveToolNames(a, mode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolA, toolB, toolC))
    }

    "union Active extras from both contributors" in Task {
      val a = agent(toolNames = List(toolA, toolB), tools = ToolPolicy.Active(List(toolC)))
      val mode: Mode = new Mode {
        val name = "m"; val description = "m"
        override val tools = ToolPolicy.Active(List(toolD))
      }
      val result = TestSigil.effectiveToolNames(a, mode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolA, toolB, toolC, toolD))
    }

    "strip baseline when agent.tools is Exclusive (mode Standard)" in Task {
      val a = agent(toolNames = List(toolA, toolB), tools = ToolPolicy.Exclusive(List(toolC)))
      val result = TestSigil.effectiveToolNames(a, standardMode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolC))
      result should not contain toolA
      result should not contain toolB
    }

    "strip baseline when mode is Exclusive (agent.tools Standard)" in Task {
      val a = agent(toolNames = List(toolA, toolB), tools = ToolPolicy.Standard)
      val mode: Mode = new Mode {
        val name = "m"; val description = "m"
        override val tools = ToolPolicy.Exclusive(List(toolC))
      }
      val result = TestSigil.effectiveToolNames(a, mode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolC))
      result should not contain toolA
    }

    "strip find_capability when either contributor is None" in Task {
      val a = agent(toolNames = List(toolA, toolB), tools = ToolPolicy.None)
      val result = TestSigil.effectiveToolNames(a, standardMode, Nil).toSet
      result shouldBe essentials
      result should not contain FindCapabilityTool.schema.name
      result should not contain toolA
    }

    "compose Exclusive (agent) + Active (mode) — both extras present, baseline stripped" in Task {
      val a = agent(toolNames = List(toolA, toolB), tools = ToolPolicy.Exclusive(List(toolC)))
      val mode: Mode = new Mode {
        val name = "m"; val description = "m"
        override val tools = ToolPolicy.Active(List(toolD))
      }
      val result = TestSigil.effectiveToolNames(a, mode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolC, toolD))
      result should not contain toolA
    }
  }

  "SkillSource.Role" should {
    "key per role-name so multiple roles don't clobber" in Task {
      SkillSource.Role("planner") should not equal SkillSource.Role("critic")
    }
  }
}
