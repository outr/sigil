package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.Sigil
import sigil.tool.{TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolResult}
import sigil.tool.core.RecordConsentTool

/**
 * Sigil #378 — `record_consent` is a no-op unless some tool in scope
 * sets `requiresUserConsent`. It's no longer in the default roster
 * (dropped from `CoreTools.all`); the framework keeps it in the per-turn
 * roster only when a consent-gated tool is actually present, so on apps
 * with no consent-gated tools it's never a dead-end attractor the model
 * loops on. Pins `Sigil.reconcileConsentTool`, the roster reconciler
 * `defaultProcess` applies.
 */
class RecordConsentRosterSpec extends AnyWordSpec with Matchers {

  private case class PlainInput() extends ToolInput derives RW
  private case object PlainTool extends Tool {
    type Input  = PlainInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[PlainInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name        = ToolName("plain_action")
    val description = "An action that needs no consent."
    override def executeResult(input: PlainInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("")))
  }

  private case class GatedInput() extends ToolInput derives RW
  private case object GatedTool extends Tool {
    type Input  = GatedInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[GatedInput]]
    val outputRW = summon[RW[TextToolOutput]]
    val name        = ToolName("gated_action")
    val description = "An action that requires user consent."
    override def requiresUserConsent: Boolean = true
    override def executeResult(input: GatedInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("")))
  }

  private val consentName = RecordConsentTool.schema.name.value
  private def names(tools: Vector[Tool]): Set[String] = tools.map(_.schema.name.value).toSet

  "Sigil.reconcileConsentTool (sigil #378)" should {

    "omit record_consent when no tool in scope requires consent" in {
      names(Sigil.reconcileConsentTool(Vector(PlainTool))) should not contain consentName
    }

    "inject record_consent when a consent-gated tool is present" in {
      names(Sigil.reconcileConsentTool(Vector(PlainTool, GatedTool))) should contain(consentName)
    }

    "drop record_consent that leaked into the roster without a consent-gated tool" in {
      names(Sigil.reconcileConsentTool(Vector(PlainTool, RecordConsentTool))) should not contain consentName
    }

    "keep exactly one record_consent when it and a consent-gated tool are both present" in {
      val out = Sigil.reconcileConsentTool(Vector(GatedTool, RecordConsentTool))
      out.count(_.schema.name.value == consentName) shouldBe 1
    }
  }
}
