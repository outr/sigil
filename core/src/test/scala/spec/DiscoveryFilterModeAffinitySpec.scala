package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import lightdb.id.Id
import rapid.Task
import sigil.{GlobalSpace, TurnContext}
import sigil.provider.{ConversationMode, Mode}
import sigil.tool.{DiscoveryFilter, DiscoveryRequest, TextToolOutput, Tool, ToolInput, ToolName, ToolResult}
import sigil.tool.ToolContext

/**
 * Coverage for sigil bug #95 — `tool.modes.isEmpty` means "no
 * mode preference; always discoverable" rather than "matches no
 * mode." Pre-fix every tool that didn't explicitly enumerate the
 * active mode in its `modes` set was filtered out of discovery,
 * even when the mode's policy was `Standard` (documented as
 * permissive). Effectively turned `Standard` into `Exclusive` for
 * any tool that hadn't tagged itself for the mode.
 */
class DiscoveryFilterModeAffinitySpec extends AnyWordSpec with Matchers {

  private case object SpecCustomMode extends Mode {
    override val name: String = "spec-custom-mode"
    override val description: String = "test-only mode for #95"
  }

  private case class StubInput(text: String = "") extends ToolInput derives RW

  /**
   * Tool that doesn't restrict its modes — should be discoverable
   * everywhere post-#95.
   */
  final private class UnrestrictedTool(n: String) extends Tool {
    type Input = StubInput
    type Output = TextToolOutput
    val inputRW = summon[RW[StubInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName(n)
    val description = s"Stub $n"
    override val modes: Set[Id[Mode]] = Set.empty

    override def executeResult(input: StubInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(input.text)))
  }

  /**
   * Tool that explicitly opts into one mode — discoverable only
   * under that mode (the existing gating behaviour).
   */
  final private class ModeRestrictedTool(n: String, restrictTo: Mode) extends Tool {
    type Input = StubInput
    type Output = TextToolOutput
    val inputRW = summon[RW[StubInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name = ToolName(n)
    val description = s"Stub $n"
    override val modes: Set[Id[Mode]] = Set(restrictTo.id)

    override def executeResult(input: StubInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput(input.text)))
  }

  private val unrestricted: Tool = new UnrestrictedTool("unrestricted_tool")
  private val customOnly: Tool = new ModeRestrictedTool("custom_only_tool", SpecCustomMode)
  private val conversationOnly: Tool = new ModeRestrictedTool("conversation_only_tool", ConversationMode)

  private def request(mode: Mode): DiscoveryRequest =
    DiscoveryRequest(
      keywords = "anything",
      chain = Nil,
      mode = mode,
      callerSpaces = Set(GlobalSpace)
    )

  "DiscoveryFilter.passesAffinity (#95)" should {

    "treat tool.modes.isEmpty as discoverable under any mode" in {
      DiscoveryFilter.passesAffinity(unrestricted, request(ConversationMode)) shouldBe true
      DiscoveryFilter.passesAffinity(unrestricted, request(SpecCustomMode)) shouldBe true
    }

    "still gate tools that explicitly populate `modes`" in {
      DiscoveryFilter.passesAffinity(customOnly, request(SpecCustomMode)) shouldBe true
      DiscoveryFilter.passesAffinity(customOnly, request(ConversationMode)) shouldBe false
      DiscoveryFilter.passesAffinity(conversationOnly, request(ConversationMode)) shouldBe true
      DiscoveryFilter.passesAffinity(conversationOnly, request(SpecCustomMode)) shouldBe false
    }

    "leave space-affinity gating unchanged" in {
      // unrestricted tool has space=GlobalSpace by default; passes regardless.
      val nonGlobalReq = request(ConversationMode).copy(callerSpaces = Set.empty)
      DiscoveryFilter.passesAffinity(unrestricted, nonGlobalReq) shouldBe true
    }
  }
}
