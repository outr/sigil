package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Args for [[BrowserSaveHtmlTool]] — no agent-supplied fields. The
 * tool captures the current browser page's outer HTML, normalizes it
 * via jSoup so the resulting bytes are XML-friendly, and persists to
 * `Sigil.storeBytes`. Returns the storage id plus a compact
 * structural overview the agent uses to plan subsequent
 * `browser_xpath_query` / `browser_text_search` calls.
 */
case class BrowserSaveHtmlInput() extends ToolInput derives RW
