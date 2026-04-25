package sigil

/**
 * Raised by the framework dispatcher when an agent's self-loop hits
 * `maxAgentIterations` without converging on a terminal exit (a `respond`
 * or `no_response`, or simply running out of new triggers).
 *
 * Indicates a real problem — typically misbehaving LLM, sparse instructions,
 * or a tool that emits a re-triggering Event without resolving the
 * underlying request. The framework releases the agent's `AgentState`
 * claim before raising, so the conversation isn't left wedged.
 */
final class AgentRunawayException(message: String) extends RuntimeException(message)
