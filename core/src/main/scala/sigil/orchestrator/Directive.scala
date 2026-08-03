package sigil.orchestrator

import fabric.rw.*
import sigil.conversation.TurnPlan

/**
 * The framework's internal control-plane language: the typed payload
 * behind every underscore-named synthetic `ToolInvoke` the framework
 * publishes to steer a running agent.
 *
 * Each case owns its wire name (stable, client-facing) and its prose
 * renderer, so the ~14 publish sites that used to hand-build both now
 * carry structure to a single render seam. The rendered text is what
 * the model reads; the typed payload rides the synthetic invoke as a
 * [[sigil.tool.DirectiveInput]] so consumers pattern-match instead of
 * parsing prose.
 *
 * Case names are wire-stable: they are persisted inside
 * [[sigil.tool.DirectiveInput]] on every synthetic invoke and read back
 * by clients and by replay, so renaming a case is a wire break.
 */
enum Directive derives RW {

  /** The planner's turn plan, handed to the executor. */
  case Plan(plan: TurnPlan)

  /** The planner judged the executor off-plan. */
  case PlannerCorrection(correction: String)

  /** A soft spend budget was crossed — cooperative check-in. */
  case BudgetCheckin(turnCost: BigDecimal,
                     conversationCost: BigDecimal,
                     scope: BudgetScope,
                     limit: BigDecimal)

  /** A hard spend ceiling was crossed — terminal synthesis. */
  case BudgetCeiling(turnCost: BigDecimal,
                     conversationCost: BigDecimal,
                     scope: BudgetScope,
                     limit: BigDecimal)

  /** The progress checkpoint's reflection nudge. */
  case ProgressCheckpoint(body: String, stuckOn: Option[String])

  /** The agent is blocked on user input and may ask directly. */
  case StallAskUser

  /** The agent is blocked but is a directed worker — it reports to its
    * supervisor rather than asking the user. */
  case StallAskSupervisor

  /** The iteration cap was reached; synthesize from what's gathered. */
  case CapReached(cap: Int)

  /** A `respond` refused the user without consulting the catalog. */
  case RefusalChallenge

  /** Plain prose carried no explicit turn decision. */
  case TurnDecisionRequired(droppedText: Option[String], priorChallenges: Int)

  /** The same `find_capability` keywords were issued twice this turn. */
  case RepeatedQueryIntercept(normalizedKeywords: String)

  /** A plain-text reply was dropped — every reply must be a tool call. */
  case PlainTextReply(droppedText: String)

  /** The model entered a token-level repetition loop. */
  case DegenerateGeneration(repeatedSentence: String,
                            occurrences: Int,
                            totalSentences: Int,
                            share: Double,
                            textLength: Int)

  /** An upstream provider error, sanitized for the agent. */
  case ProviderError(sanitizedMessage: String)

  /** XML tool-call syntax leaked into `respond.content`. */
  case XmlToolCallLeak(firstLeakedExcerpt: String)

  /** Whether the directive stays in context once read.
    *
    * A durable directive states something the agent must keep judging
    * against for the rest of the turn — the plan is the standing
    * objective, and dropping it leaves the planner correcting against a
    * plan the executor can no longer see. Everything else is a
    * transient nudge aimed at ONE next iteration; stale copies are
    * context noise the curator sheds. */
  def durable: Boolean = this match {
    case _: Plan => true
    case _       => false
  }

  /** The synthetic invoke's tool name. Stable and client-facing —
    * clients and persisted history key off these. */
  def wireName: String = this match {
    case _: Plan                 => Directive.PlanName
    case _: PlannerCorrection    => "_planner_correction"
    case _: BudgetCheckin        => "_budget_checkin"
    case _: BudgetCeiling        => "_budget_ceiling"
    case _: ProgressCheckpoint   => Directive.StallDetectedName
    case StallAskUser            => Directive.StallDetectedName
    case StallAskSupervisor      => Directive.StallDetectedName
    case _: CapReached           => Directive.CapReachedName
    case RefusalChallenge        => Directive.RefusalChallengeName
    case _: TurnDecisionRequired => Orchestrator.TurnDecisionToolName
    case _: RepeatedQueryIntercept => Directive.RepeatedQueryInterceptName
    case _: PlainTextReply       => "_plain_text_reply"
    case _: DegenerateGeneration => "_degenerate_generation"
    case _: ProviderError        => "_provider_error"
    case _: XmlToolCallLeak      => sigil.provider.XmlToolCallSanitizer.SyntheticInvokeName
  }

  /** The prose the model reads. */
  def render: String = this match {
    case Plan(plan) =>
      val constraints =
        if (plan.constraints.isEmpty) ""
        else plan.constraints.map(c => s"  - $c").mkString("\nConstraints:\n", "\n", "")
      val phase = plan.currentPhase.map(p => s"\nCurrent phase: $p").getOrElse("")
      "[plan — internal, not a user message; do NOT reply to or acknowledge this in chat]\n" +
        s"Objective: ${plan.objective}$constraints\nDone when: ${plan.doneCriteria}$phase"

    case PlannerCorrection(correction) =>
      "[planner correction — internal, not a user message; do NOT reply to or acknowledge this in chat]\n" +
        correction + " Adjust your approach now and continue the task. Do not apologize for or narrate " +
        "this correction to the user; just do the work."

    case BudgetCheckin(turnCost, conversationCost, scope, limit) =>
      s"[spend check-in — internal, not a user message] ${Directive.spendLine(turnCost, conversationCost)} " +
        s"That crosses the ${scope.label} soft budget " +
        s"(${Directive.fmt(limit)}). Summarize where you are and what remains, then ask the user via `respond_options` " +
        "whether to continue and at what scope — do not keep working until they answer. If the remaining " +
        "work is mechanical, call `request_deescalation` before continuing so it runs on a cheaper tier."

    case BudgetCeiling(turnCost, conversationCost, scope, limit) =>
      val which = scope match {
        case BudgetScope.PerTurn      => "hard per-turn ceiling"
        case BudgetScope.Conversation => "hard conversation ceiling"
      }
      s"[spend ceiling — internal, not a user message] ${Directive.spendLine(turnCost, conversationCost)} " +
        s"That crosses the $which " +
        s"(${Directive.fmt(limit)}). Call `respond` NOW with an honest report: what you accomplished, what remains, " +
        "and what was spent. Do not start further work."

    case ProgressCheckpoint(body, stuckOn) =>
      val hint = stuckOn.map(s => s" You are stuck on: $s.").getOrElse("")
      "[progress checkpoint — internal, not a user message; do NOT reply to or acknowledge this in chat]\n" +
        body.trim + hint +
        " Change your approach now and continue the task. Do not apologize for or narrate this correction " +
        "to the user; just do the work."

    case StallAskSupervisor =>
      "You can't ask the user directly from here — your supervisor owns this task. " +
        "Stop gathering and call `respond` now to report what you've found and what you're blocked on."

    case StallAskUser =>
      "You appear blocked waiting on input. First check whether your recent tool calls actually " +
        "completed (their results may be large and externalized rather than inline) — if so, just " +
        "continue. If you genuinely need the user, ask them directly NOW via `respond_options` " +
        "(clickable choices, preferred) or `respond`, phrasing the question yourself."

    case CapReached(cap) =>
      s"You've reached the iteration cap ($cap turns) for this user request. " +
        "Synthesize a response NOW from what you've gathered so far — call `respond` with " +
        "your findings. Do not call any more discovery / read / search tools."

    case RefusalChallenge =>
      "Your previous `respond` refused the user without first calling `find_capability` (see the " +
        "system prompt — a refusal not preceded by `find_capability` is a bug). The tool catalog " +
        "likely contains capabilities you haven't discovered. Call `find_capability` with keywords " +
        "describing what the user asked, review the matches, then decide whether to refuse based on " +
        "what discovery actually returns. If no relevant capability surfaces, refuse with the " +
        "specifics of what you searched and what wasn't there."

    case TurnDecisionRequired(droppedText, priorChallenges) =>
      val escalation =
        if (priorChallenges <= 0) ""
        else s"You have now produced ${priorChallenges + 1} plain-prose replies in a row without acting — " +
          "you announced work and did not do it. Do NOT narrate again. "
      droppedText match {
        case Some(text) =>
          escalation +
            "Your previous reply was plain prose and was dropped — prose carries no explicit turn decision " +
            "(`respond`'s required `endsTurn`). Decide now: if the work for this turn is complete, call " +
            "`respond` with your answer and `endsTurn = true`. If your reply announced further steps, execute " +
            s"them now with tool calls instead of stopping. Dropped text was: ${Directive.snippet(text)}"
        case None =>
          escalation +
            "Your previous reply was plain prose, which carries no explicit turn decision (`respond`'s required " +
            "`endsTurn`). The message HAS been delivered to the user — do not repeat it. Decide now: if the " +
            "work for this turn is complete and that message was your full answer, call `no_response` to end " +
            "the turn. If your reply announced further steps, execute them now with tool calls instead of " +
            "stopping. If you still owe the user a final answer, call `respond` with `endsTurn = true`."
      }

    case RepeatedQueryIntercept(normalizedKeywords) =>
      s"You already called `find_capability` with keywords `$normalizedKeywords` earlier this " +
        "turn. The ranker is deterministic — the same keywords will return the same hits. " +
        "Either pick a different tool from the prior results (review the previous " +
        "`find_capability` result Message in your context), or call `find_capability` again " +
        "with DIFFERENT keywords that describe the action shape more specifically. Repeating " +
        "the same search will not produce a different answer."

    case PlainTextReply(droppedText) =>
      "Your previous reply was plain text and was dropped — every reply must be a " +
        "tool call. Wrap your answer in one of the respond-family tools " +
        "(`respond`, `respond_options`, `no_response`) appropriate to your situation. " +
        "When a tool result IS the " +
        s"user-facing answer, call `respond` with that content. Dropped text was: ${Directive.snippet(droppedText)}"

    case DegenerateGeneration(repeatedSentence, occurrences, totalSentences, share, textLength) =>
      val sharePct = math.round(share * 100).toInt
      val snip = if (repeatedSentence.length <= 200) repeatedSentence else repeatedSentence.take(200) + "…"
      s"Model entered a token-level repetition loop ($occurrences/$totalSentences sentences = $sharePct% were the same) before hitting max_tokens. " +
        s"$textLength chars of redundant output. " +
        s"Repeated sentence: \"$snip\".\n\n" +
        "Your prior approach is stuck. Either call `find_capability` for a different shape of action, " +
        "invoke a concrete tool that advances the task, or set `endsTurn = true` on a respond asking " +
        "the user to narrow the scope. Do NOT retry the same content."

    case ProviderError(sanitizedMessage) => s"Provider error: $sanitizedMessage"

    case XmlToolCallLeak(firstLeakedExcerpt) =>
      sigil.provider.XmlToolCallSanitizer.interventionDirective(firstLeakedExcerpt)
  }
}

object Directive {

  val PlanName: String = "_plan"
  val RefusalChallengeName: String = "_refusal_challenge"
  val RepeatedQueryInterceptName: String = "_repeated_query_intercept"
  val StallDetectedName: String = "_stall_detected"
  val CapReachedName: String = "_cap_reached"

  /** Wire names of the directives whose frames survive the curator's
    * stale-internal-frame shed. Membership source for consumers that
    * classify a synthetic invoke by name alone — a persisted frame
    * carries the wire name, not the typed payload. */
  val durableWireNames: Set[String] = Set(PlanName)

  private[sigil] def fmt(v: BigDecimal): String = f"$$${v}%.2f"

  private[sigil] def spendLine(turnCost: BigDecimal, conversationCost: BigDecimal): String =
    s"This turn has spent ${fmt(turnCost)}; the conversation total is ${fmt(conversationCost)}."

  private[sigil] def snippet(text: String): String =
    if (text.length <= 200) text else text.take(200) + "…"
}
