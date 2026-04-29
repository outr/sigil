package sigil.browser

import sigil.conversation.ActiveSkillSlot
import sigil.provider.{BuiltInTool, Mode, ToolPolicy}
import sigil.tool.ToolName

/**
 * Conversation mode that activates the headless-browser tool roster.
 * Apps register via `Sigil.modes` to make it switchable; agents
 * usually `change_mode("web-browser")` after `find_capability`
 * surfaces the browser tools.
 *
 * `ToolPolicy.Exclusive` keeps unrelated tools out of the agent's
 * roster while the mode is active — the agent has the framework
 * essentials (`respond`, `change_mode`, `stop`) plus the primitive
 * browser tools, nothing else. Discovery via `find_capability` is
 * suppressed (no rummaging mid-task); the agent already knows what
 * it has.
 *
 * Native provider built-ins are NOT enabled here — when the agent
 * is in a real browser, the browser is the search tool. Apps that
 * want both `googleSearch`-style grounding AND the browser override
 * `WebBrowserMode.builtInTools` and union them.
 */
case object WebBrowserMode extends Mode {
  override val name: String = "web-browser"

  override val description: String =
    "Drive a real headless browser. Navigate, click, type, scroll, screenshot, save HTML, and run structural queries (XPath / text search) over saved pages."

  override val skill: Option[ActiveSkillSlot] = Some(ActiveSkillSlot(
    name = "web-browser",
    content =
      """You drive a real headless Chrome browser. Standard pipeline for any research task:
        |
        |1. `browser_navigate(url)` — load the page.
        |2. `browser_save_html()` — persist the page and read the returned overview. The overview lists
        |   `headings`, `landmarks` (main/nav/article/aside/header/footer), `linkClusters` (parent
        |   xpaths with multiple anchors), and `totals`. The full HTML is NOT in your prompt — you query it.
        |3. Plan an xpath from the overview. Headings give you section anchors; linkClusters tell you where
        |   the meaningful link lists are; landmarks tell you which container to scope to.
        |4. `browser_xpath_query(htmlFileId, xpath)` — extract specific structural fragments. Set
        |   `includeOuterHtml=true` only when you need raw markup; default returns `{tag, text, attributes}`.
        |5. `browser_text_search(htmlFileId, query)` — locate content; each hit returns a `containingXPath`
        |   you can pivot back through `browser_xpath_query`.
        |6. After interactive steps (click / type / scroll) the page state changes — call `browser_save_html`
        |   again to refresh the overview and the queryable HTML id.
        |7. `browser_screenshot` when text-only doesn't suffice (graphical UIs, layout-dependent pages).
        |
        |Stop calling tools once you have the answer; respond with a concise summary.
        |""".stripMargin
  ))

  override val tools: ToolPolicy = ToolPolicy.Exclusive(List(
    ToolName("browser_navigate"),
    ToolName("browser_screenshot"),
    ToolName("browser_save_html"),
    ToolName("browser_xpath_query"),
    ToolName("browser_text_search"),
    ToolName("browser_click"),
    ToolName("browser_type"),
    ToolName("browser_scroll")
  ))

  override val builtInTools: Set[BuiltInTool] = Set.empty
}
