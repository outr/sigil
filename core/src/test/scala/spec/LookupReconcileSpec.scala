package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.Sigil
import sigil.conversation.{ContextMemory, Conversation, TurnInput}
import sigil.information.{Information, InformationSummary, StoredInformation}
import sigil.tool.Tool
import sigil.tool.core.{CoreTools, RespondTool}
import sigil.tool.util.LookupTool

/**
 * `lookup` follows the `record_consent` pattern: always registered
 * (the rendered context advertises it via `[full: lookup("…")]`
 * handles and `Information[…]` references, so the name must always
 * resolve), advertised in the per-turn roster exactly when the turn's
 * input carries something to look up.
 */
class LookupReconcileSpec extends AnyWordSpec with Matchers {

  private val base: Vector[Tool] = Vector(RespondTool)
  private def input(memories: Vector[Id[ContextMemory]] = Vector.empty,
                    critical: Vector[Id[ContextMemory]] = Vector.empty,
                    information: Vector[InformationSummary] = Vector.empty): TurnInput =
    TurnInput(
      conversationId = Conversation.id("lookup-reconcile"),
      memories = memories,
      criticalMemories = critical,
      information = information
    )

  "lookup registration" should {
    "live in CoreTools.all so the name always resolves" in {
      CoreTools.all should contain (LookupTool)
    }
  }

  "reconcileLookupTool" should {
    "advertise lookup when the turn carries retrieved memories" in {
      val tools = Sigil.reconcileLookupTool(base, input(memories = Vector(ContextMemory.id("m1"))))
      tools.map(_.schema.name.value) should contain ("lookup")
    }

    "advertise lookup when the turn carries pinned memories" in {
      val tools = Sigil.reconcileLookupTool(base, input(critical = Vector(ContextMemory.id("p1"))))
      tools.map(_.schema.name.value) should contain ("lookup")
    }

    "advertise lookup when the turn carries information summaries" in {
      val info = InformationSummary(Id[Information]("i1"), Information.name.of[StoredInformation], "a note")
      val tools = Sigil.reconcileLookupTool(base, input(information = Vector(info)))
      tools.map(_.schema.name.value) should contain ("lookup")
    }

    "not duplicate an already-present lookup" in {
      val tools = Sigil.reconcileLookupTool(base :+ LookupTool, input(memories = Vector(ContextMemory.id("m1"))))
      tools.count(_.schema.name.value == "lookup") shouldBe 1
    }

    "not inject lookup when the turn has nothing to look up" in {
      val tools = Sigil.reconcileLookupTool(base, input())
      tools.map(_.schema.name.value) should not contain "lookup"
    }

    "leave an explicitly-rostered lookup in place even with nothing to look up" in {
      // Unlike record_consent, lookup is never a pure no-op — the agent
      // may hold keys learned from history — so the reconcile is
      // add-only and respects an app's explicit roster choice.
      val tools = Sigil.reconcileLookupTool(base :+ LookupTool, input())
      tools.map(_.schema.name.value) should contain ("lookup")
    }

    "stay out of the default advertised roster names" in {
      CoreTools.coreToolNames should not contain LookupTool.schema.name
    }
  }
}
