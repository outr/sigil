package sigil.provider

import sigil.tool.ToolName

/**
 * How aggressively the provider should make the model invoke a tool, on
 * a given [[ProviderCall]].
 */
enum ToolChoice {

  /** No tools provided; free-form text response. */
  case None

  /** Tools are available; the model decides whether to call one. */
  case Auto

  /** Tools are available and the model MUST call one — no free-form
    * text response permitted. Used for the respond tool path and for
    * structured-output sub-calls (consult, classifier). */
  case Required

 /** Tools are available and the model MUST call this specific one. */
  case Specific(toolName: ToolName)
}

object ToolChoice {

  extension (tc: ToolChoice) {

    /** Whether this choice forces tool use (`Required` →
      * Anthropic `{type:"any"}`, `Specific` → `{type:"tool"}`). Some
      * models reject forced tool use entirely and accept only
      * `Auto`/`None`; sigil #387's self-heal downgrades a forced
      * choice to [[ToolChoice.Auto]] when the model rejects it. */
    def isForced: Boolean = tc match {
      case Required | Specific(_) => true
      case None | Auto            => false
    }
  }
}
