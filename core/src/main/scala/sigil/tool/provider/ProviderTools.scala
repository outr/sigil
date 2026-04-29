package sigil.tool.provider

import sigil.tool.{Tool, ToolName}

/**
 * Convenience aggregator for the framework's provider-management
 * tools. Apps that want to expose strategy-switching to the agent
 * extend their `Sigil.staticTools` with this list:
 *
 * {{{
 *   override def staticTools: List[Tool] = super.staticTools ++ ProviderTools.all
 * }}}
 *
 * **NOT auto-registered.** Strategy switching is privileged — the
 * framework deliberately requires apps to opt in. Apps that want
 * tool-driven switching but with stricter authz override
 * `Sigil.accessibleSpaces` so the tools' authz check fails for
 * unauthorized callers.
 */
object ProviderTools {
  val all: List[Tool] = List(SwitchModelTool, ListProviderStrategiesTool)

  val toolNames: List[ToolName] = all.map(_.name)
}
