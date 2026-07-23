package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.conversation.ConversationBudget
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

final case class SetBudgetInput(@description("Soft per-turn spend budget in USD — crossing it makes the agent pause, summarize, and ask whether to continue. Omit to leave unchanged.")
                                turnSoft: Option[BigDecimal] = None,
                                @description("Hard per-turn spend ceiling in USD — crossing it ends the turn with a spend-and-state report. Omit to leave unchanged.")
                                turnHard: Option[BigDecimal] = None,
                                @description("Soft whole-conversation spend budget in USD. Omit to leave unchanged.")
                                conversationSoft: Option[BigDecimal] = None,
                                @description("Hard whole-conversation spend ceiling in USD — a conversation past it refuses new turns until raised. Omit to leave unchanged.")
                                conversationHard: Option[BigDecimal] = None,
                                @description("True to clear the conversation's budget override entirely, reverting every threshold to the application default. Supplied values are ignored when set.")
                                clear: Boolean = false)
  extends ToolInput derives RW

/**
 * Set (or clear) this conversation's spend-budget override — the
 * conversational surface for "cap this at $5". Each supplied field
 * overrides the matching app-level default for this conversation
 * only; omitted fields keep their current value. Raising a hard
 * ceiling re-arms a budget-exhausted conversation.
 */
case object SetBudgetTool extends Tool {
  type Input  = SetBudgetInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[SetBudgetInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("set_budget")
  val description =
    """Set or clear this conversation's spend-budget override (USD). Soft budgets make the agent
      |pause at a summary and ask whether to continue; hard ceilings end the turn (per-turn) or
      |block new turns (conversation) until raised. Supplied fields override the application
      |default for THIS conversation; omitted fields are unchanged; `clear = true` removes the
      |whole override. Use when the user says things like "cap this at $5" or "raise the budget
      |to $20".""".stripMargin
  override val examples = List(
    ToolExample("Cap the conversation at $5", SetBudgetInput(conversationHard = Some(BigDecimal(5)))),
    ToolExample("Pause for approval every $2 of turn spend", SetBudgetInput(turnSoft = Some(BigDecimal(2)))),
    ToolExample("Remove the conversation's budget override", SetBudgetInput(clear = true))
  )
  override val keywords = Set(
    "budget", "spend", "cost", "cap", "limit", "ceiling", "dollars",
    "expensive", "afford", "money", "price"
  )

  override def executeResult(input: SetBudgetInput, context: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val sigil = context.sigil
    val convId = context.conversation._id
    sigil.withDB(_.conversations.transaction(_.modify(convId) {
      case None => Task.pure(None)
      case Some(conv) =>
        val next =
          if (input.clear) None
          else {
            val cur = conv.budget.getOrElse(ConversationBudget())
            Some(cur.copy(
              turnSoft         = input.turnSoft.orElse(cur.turnSoft),
              turnHard         = input.turnHard.orElse(cur.turnHard),
              conversationSoft = input.conversationSoft.orElse(cur.conversationSoft),
              conversationHard = input.conversationHard.orElse(cur.conversationHard)
            ))
          }
        Task.pure(Some(conv.copy(budget = next, modified = lightdb.time.Timestamp())))
    })).map {
      case None => ToolResult.failure("Conversation not found — the budget was not changed.")
      case Some(updated) =>
        // A raised (or cleared) ceiling re-arms an exhausted
        // conversation: the one-shot exhaustion notice may fire again
        // if the new ceiling is crossed later.
        sigil.clearBudgetExhaustedNotice(convId)
        val eff = sigil.effectiveBudgetsFor(updated)
        def fmt(o: Option[BigDecimal]): String = o.map(v => f"$$${v}%.2f").getOrElse("unset")
        ToolResult.Success(TextToolOutput(
          s"Budget updated. Effective thresholds — turn soft: ${fmt(eff.turnSoft)}, turn hard: ${fmt(eff.turnHard)}, " +
            s"conversation soft: ${fmt(eff.conversationSoft)}, conversation hard: ${fmt(eff.conversationHard)}. " +
            f"Conversation spend so far: $$${updated.cost}%.2f."))
    }
  }
}
