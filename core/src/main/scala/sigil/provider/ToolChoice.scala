package sigil.provider

import sigil.tool.ToolName

/**
 * How aggressively the provider should make the model invoke a tool, on
 * a given [[ProviderCall]].
 */
enum ToolChoice {

  /**
   * No tools provided; free-form text response.
   */
  case None

  /**
   * Tools are available; the model decides whether to call one.
   */
  case Auto

  /**
   * Tools are available and the model MUST call one — no free-form
   * text response permitted. Used for the respond tool path and for
   * structured-output sub-calls (consult, classifier).
   */
  case Required

  /**
   * Tools are available and the model MUST call this specific one.
   * Sigil bug #125 — used by the iteration-cap soft-stop path to
   * force a `respond` synthesis from the agent's gathered context
   * rather than throwing [[sigil.AgentRunawayException]] and
   * discarding whatever the agent has built up.
   */
  case Specific(toolName: ToolName)
}
