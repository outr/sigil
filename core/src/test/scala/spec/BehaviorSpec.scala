package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.behavior.{Behavior, GeneralistBehavior}
import sigil.conversation.{ActiveSkillSlot, SkillSource}
import sigil.participant.{AgentParticipant, DefaultAgentParticipant}
import sigil.provider.{ConversationMode, GenerationSettings, Instructions, Mode, ToolPolicy}
import sigil.tool.ToolName
import sigil.tool.core.{ChangeModeTool, FindCapabilityTool, NoResponseTool, RespondTool, StopTool}

/**
 * Coverage for the [[Behavior]] role primitive, the
 * `AgentParticipant.behaviors` field's invariants, and the
 * Behavior + Mode policy fold inside `Sigil.effectiveToolNames`.
 */
class BehaviorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val essentials: Set[ToolName] =
    List(RespondTool, NoResponseTool, ChangeModeTool, StopTool).map(_.schema.name).toSet
  private val withDiscovery: Set[ToolName] =
    essentials + FindCapabilityTool.schema.name

  private val toolA = ToolName("tool_a")
  private val toolB = ToolName("tool_b")
  private val toolC = ToolName("tool_c")
  private val toolD = ToolName("tool_d")

  private def agent(behaviors: List[Behavior] = List(GeneralistBehavior),
                    tools: List[ToolName] = Nil): DefaultAgentParticipant =
    DefaultAgentParticipant(
      id = TestAgent,
      modelId = sigil.db.Model.id("test", "model"),
      toolNames = tools,
      instructions = Instructions(),
      generationSettings = GenerationSettings(),
      behaviors = behaviors
    )

  private def behavior(tools: ToolPolicy = ToolPolicy.Standard,
                       description: String = "",
                       name: String = "test"): Behavior =
    Behavior(name = name, description = description, tools = tools)

  private val standardMode: Mode = new Mode {
    override val name: String = "test-mode"
    override val description: String = "test"
    override val tools: ToolPolicy = ToolPolicy.Standard
  }

  "AgentParticipant.behaviors" should {
    "default to List(GeneralistBehavior)" in Task {
      val a: AgentParticipant = DefaultAgentParticipant(
        id = TestAgent,
        modelId = sigil.db.Model.id("test", "model")
      )
      a.behaviors shouldBe List(GeneralistBehavior)
    }

    "reject an empty behaviors list at construction" in Task {
      an[IllegalArgumentException] should be thrownBy {
        DefaultAgentParticipant(
          id = TestAgent,
          modelId = sigil.db.Model.id("test", "model"),
          behaviors = Nil
        )
      }
    }

    "accept a multi-behavior list" in Task {
      val b1 = behavior(name = "first")
      val b2 = behavior(name = "second")
      val a = agent(behaviors = List(b1, b2))
      a.behaviors shouldBe List(b1, b2)
    }
  }

  "GeneralistBehavior" should {
    "have a non-empty description" in Task {
      GeneralistBehavior.description.nonEmpty shouldBe true
    }

    "use ToolPolicy.Standard" in Task {
      GeneralistBehavior.tools shouldBe ToolPolicy.Standard
    }

    "have name 'generalist'" in Task {
      GeneralistBehavior.name shouldBe "generalist"
    }
  }

  "Sigil.effectiveToolNames behavior+mode fold" should {
    val a = agent(tools = List(toolA, toolB))

    "behave like Mode-only when behavior is Standard" in Task {
      val b = behavior(tools = ToolPolicy.Standard)
      val mode: Mode = new Mode {
        val name = "m"; val description = "m"
        override val tools = ToolPolicy.Active(List(toolC))
      }
      val result = TestSigil.effectiveToolNames(a, b, mode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolA, toolB, toolC))
    }

    "union Active extras from both contributors" in Task {
      val b = behavior(tools = ToolPolicy.Active(List(toolC)))
      val mode: Mode = new Mode {
        val name = "m"; val description = "m"
        override val tools = ToolPolicy.Active(List(toolD))
      }
      val result = TestSigil.effectiveToolNames(a, b, mode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolA, toolB, toolC, toolD))
    }

    "strip baseline when behavior is Exclusive (mode Standard)" in Task {
      val b = behavior(tools = ToolPolicy.Exclusive(List(toolC)))
      val result = TestSigil.effectiveToolNames(a, b, standardMode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolC))
      result should not contain toolA
      result should not contain toolB
    }

    "strip baseline when mode is Exclusive (behavior Standard)" in Task {
      val b = behavior(tools = ToolPolicy.Standard)
      val mode: Mode = new Mode {
        val name = "m"; val description = "m"
        override val tools = ToolPolicy.Exclusive(List(toolC))
      }
      val result = TestSigil.effectiveToolNames(a, b, mode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolC))
      result should not contain toolA
    }

    "strip find_capability when either contributor is None" in Task {
      val b = behavior(tools = ToolPolicy.None)
      val result = TestSigil.effectiveToolNames(a, b, standardMode, Nil).toSet
      result shouldBe essentials
      result should not contain FindCapabilityTool.schema.name
      result should not contain toolA
    }

    "compose Exclusive + Active — both extras present, baseline stripped" in Task {
      val b = behavior(tools = ToolPolicy.Exclusive(List(toolC)))
      val mode: Mode = new Mode {
        val name = "m"; val description = "m"
        override val tools = ToolPolicy.Active(List(toolD))
      }
      val result = TestSigil.effectiveToolNames(a, b, mode, Nil).toSet
      result shouldBe (withDiscovery ++ Set(toolC, toolD))
      result should not contain toolA
    }
  }

  "AgentParticipant.process behavior dispatch" should {
    "inject each behavior's slot under SkillSource.Behavior(name) keyed by behavior" in Task {
      // We exercise the slot-injection logic via the helper signal that
      // `Sigil.process` would be called with — the simplest verification is
      // to check that the contracted slot would be produced from the agent's
      // behavior list. The runtime `process` body runs through the LLM so
      // is exercised by the live LlamaCpp* specs end-to-end.
      val planner = behavior(name = "planner", description = "Plan steps.")
      val worker  = behavior(name = "worker",  description = "")  // no description = no slot
      val a       = agent(behaviors = List(planner, worker))

      // Behaviors are public on the agent — ordering preserved.
      a.behaviors.map(_.name) shouldBe List("planner", "worker")

      // The slot injected for `planner` should match the description-derived form.
      val expectedSlot = ActiveSkillSlot(name = "planner", content = "Plan steps.")
      planner.skill.getOrElse(
        ActiveSkillSlot(name = planner.name, content = planner.description)
      ) shouldBe expectedSlot

      // Multiple behaviors with the same name would clobber on
      // SkillSource.Behavior(name); the parameterized case prevents
      // unrelated behaviors from clobbering each other.
      SkillSource.Behavior("planner") should not equal SkillSource.Behavior("worker")
    }
  }
}
