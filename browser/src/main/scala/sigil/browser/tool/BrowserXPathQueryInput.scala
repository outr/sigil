package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Args for [[BrowserXPathQueryTool]]. Runs `xpath` against the saved
 * HTML referenced by `htmlFileId` (typically the `htmlFileId` returned
 * by the most recent `browser_save_html`).
 *
 * `maxResults` caps the returned matches; `includeOuterHtml` decides
 * whether each result includes the matched node's full HTML or just
 * tag + text + attributes.
 */
case class BrowserXPathQueryInput(htmlFileId: String,
                                  xpath: String,
                                  maxResults: Int = 20,
                                  includeOuterHtml: Boolean = false) extends ToolInput derives RW
