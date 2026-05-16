package sigil

/**
 * Raised by the framework dispatcher when an agent's self-loop fails to
 * converge on a terminal exit (a `respond` or `no_response`).
 *
 * Indicates a real problem — typically misbehaving LLM, sparse instructions,
 * or a tool that emits a re-triggering Event without resolving the
 * underlying request. The framework releases the agent's `AgentState`
 * claim before raising, so the conversation isn't left wedged.
 *
 * The `reason` distinguishes which forced-synthesis trigger condition
 * led here so the message attribution matches reality (sigil bug #198) —
 * `CapHit` actually means "iteration counter reached
 * `maxAgentIterations`"; `NoToolCall` means "model returned without
 * calling any tool on iteration ≥ 2 despite `tool_choice: required`";
 * `StallIntervention` means "progress-checkpoint detected stall and
 * forced respond".
 */
final class AgentRunawayException(message: String,
                                  val reason: ForcedSynthesisReason)
  extends RuntimeException(message)

/**
 * Which condition triggered the forced-synthesis recovery turn that
 * eventually failed. Carried on [[AgentRunawayException]] so consumer
 * dashboards and operator runbooks can route on the actual cause
 * rather than reading the message string (sigil bug #198).
 */
enum ForcedSynthesisReason {
  /** Iteration counter reached `maxAgentIterations`. */
  case CapHit
  /** Model returned without calling any tool despite
    * `tool_choice: required` — typically a weak / non-instruction-
    * following local model. */
  case NoToolCall
  /** Progress-checkpoint intervention. */
  case StallIntervention
}
