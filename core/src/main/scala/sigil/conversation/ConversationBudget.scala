package sigil.conversation

import fabric.rw.*

/**
 * Per-conversation spend-budget override (USD), set conversationally
 * via the `set_budget` tool or by the app. Each field, when set,
 * overrides the matching [[sigil.Sigil]] hook
 * (`turnCostSoftBudget` / `turnCostHardCeiling` /
 * `conversationCostSoftBudget` / `conversationCostHardCeiling`) for
 * this conversation only; unset fields inherit the app default.
 *
 * Semantics of the four thresholds:
 *
 *   - **soft** — crossing it injects a check-in directive: the agent
 *     summarizes where it is and asks the user (via
 *     `respond_options`) whether to continue and at what scope. The
 *     turn yields; the user's continuation is a fresh turn with a
 *     fresh turn budget (and a fresh complexity classification — the
 *     check-in is also the de-escalation point).
 *   - **hard** — crossing it forces terminal synthesis: the agent
 *     wraps up honestly with a spend-and-state report and the turn
 *     ends. A conversation already past its hard ceiling refuses to
 *     start new turns until the budget is raised.
 */
case class ConversationBudget(turnSoft: Option[BigDecimal] = None,
                              turnHard: Option[BigDecimal] = None,
                              conversationSoft: Option[BigDecimal] = None,
                              conversationHard: Option[BigDecimal] = None)
  derives RW
