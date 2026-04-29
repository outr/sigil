package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Args for [[BrowserTextSearchTool]]. Substring-search the body
 * text of the saved HTML referenced by `htmlFileId`. Returns a small
 * array of matches with surrounding context — enough for the agent
 * to locate content without loading the whole document into its
 * prompt.
 *
 * Agents usually call `browser_save_html` first to get the
 * `htmlFileId`, then alternate `browser_text_search` (find content)
 * with `browser_xpath_query` (extract structure around it).
 */
case class BrowserTextSearchInput(htmlFileId: String,
                                  query: String,
                                  contextChars: Int = 120,
                                  maxResults: Int = 20,
                                  caseSensitive: Boolean = false) extends ToolInput derives RW
