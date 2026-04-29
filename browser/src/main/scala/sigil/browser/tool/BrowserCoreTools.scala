package sigil.browser.tool

import sigil.tool.{Tool, ToolName}

/**
 * Convenience aggregator for the primitive browser tools. Apps that
 * include `sigil-browser` typically extend their `Sigil.staticTools`
 * with this list:
 *
 * {{{
 *   override def staticTools: List[Tool] =
 *     super.staticTools ++ BrowserCoreTools.all
 * }}}
 *
 * Apps that want a subset (e.g. read-only research agents that
 * shouldn't `click` or `type`) cherry-pick from the individual
 * `*Tool` classes.
 *
 * The roster is structured around a structural-query pipeline: the
 * agent navigates, calls `browser_save_html` to persist the page
 * and receive a compact overview, then drives `browser_xpath_query`
 * / `browser_text_search` against the saved bytes — the LLM never
 * sees the raw HTML in its context.
 */
object BrowserCoreTools {
  val all: List[Tool] = List(
    new BrowserNavigateTool,
    new BrowserScreenshotTool,
    new BrowserSaveHtmlTool,
    new BrowserXPathQueryTool,
    new BrowserTextSearchTool,
    new BrowserClickTool,
    new BrowserTypeTool,
    new BrowserScrollTool
  )

  val toolNames: List[ToolName] = all.map(_.name)
}
