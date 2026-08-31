package spec

import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.TurnPlan
import sigil.orchestrator.{BudgetScope, Directive}
import sigil.tool.{DirectiveInput, ToolInput}

/**
 * Every [[Directive]] rides a synthetic `ToolInvoke` as a
 * [[DirectiveInput]] and is persisted with the event. A case that
 * doesn't survive `read(write(d))` breaks conversation replay for every
 * turn that carried it — and the case NAMES are the wire discriminator,
 * so renaming one is a wire break, not a refactor.
 */
class DirectivePersistenceSpec extends AnyWordSpec with Matchers {
  // `DirectiveInput` round-trips through the polymorphic `ToolInput`
  // discriminator, which the framework populates at registration.
  TestSigil.polymorphicRegistrations.sync()

  private val all: List[Directive] = List(
    Directive.Plan(TurnPlan(
      objective = "ship the fix",
      constraints = List("no new deps"),
      doneCriteria = "tests green",
      currentPhase = Some("implementing"))),
    Directive.PlannerCorrection("realign with the objective"),
    Directive.BudgetCheckin(BigDecimal("1.25"), BigDecimal("4.50"), BudgetScope.PerTurn, BigDecimal("1.00")),
    Directive.BudgetCeiling(BigDecimal("9.99"), BigDecimal("12.00"), BudgetScope.Conversation, BigDecimal("10.00")),
    Directive.ProgressCheckpoint("no progress in 6 iterations", Some("the read-edit loop")),
    Directive.ProgressCheckpoint("no progress", None),
    Directive.StallAskUser,
    Directive.StallAskSupervisor,
    Directive.CapReached(30),
    Directive.RefusalChallenge,
    Directive.TurnDecisionRequired(Some("I'll go do that now"), 2),
    Directive.TurnDecisionRequired(None, 0),
    Directive.RepeatedQueryIntercept("bug references"),
    Directive.PlainTextReply("here is my answer"),
    Directive.DegenerateGeneration("the same sentence", 12, 20, 0.6, 4096),
    Directive.ProviderError("upstream returned 503"),
    Directive.XmlToolCallLeak("<tool_call>{\"name\":\"grep\"}")
  )

  "Directive persistence" should {

    "round-trip every case through its RW" in {
      val rw = summon[RW[Directive]]
      all.foreach { d =>
        withClue(s"$d: ")(rw.write(rw.read(d)) shouldBe d)
      }
      succeed
    }

    "round-trip inside the DirectiveInput the synthetic invoke carries" in {
      val rw = summon[RW[ToolInput]]
      all.foreach { d =>
        val input: ToolInput = DirectiveInput(d)
        withClue(s"$d: ")(rw.write(rw.read(input)) shouldBe input)
      }
      succeed
    }

    "keep every case's wire name stable and non-empty" in {
      all.foreach(d => withClue(s"$d: ")(d.wireName should startWith("_")))
      succeed
    }

    "render prose for every case" in {
      all.foreach(d => withClue(s"$d: ")(d.render.trim should not be empty))
      succeed
    }

    "mark the plan durable and every nudge transient" in {
      all.foreach { d =>
        withClue(s"$d: ") {
          d.durable shouldBe d.isInstanceOf[Directive.Plan]
        }
      }
      Directive.durableWireNames shouldBe Set(Directive.PlanName)
      succeed
    }
  }
}
