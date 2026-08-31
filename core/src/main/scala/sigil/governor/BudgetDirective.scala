package sigil.governor

import sigil.orchestrator.Directive

/**
 * A budget-gate verdict: `hard = true` forces terminal synthesis;
 * `hard = false` is the cooperative check-in. `directive` is the
 * Tool-role directive the agent reads.
 */
final private[sigil] case class BudgetDirective(hard: Boolean, directive: Directive)
