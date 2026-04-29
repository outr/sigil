package sigil.browser

import fabric.rw.*

/**
 * One step of a [[BrowserScript]]. Steps are applied in order against
 * the per-conversation [[BrowserController]]; each maps roughly to one
 * primitive browser tool.
 *
 * `${arg}` placeholders in any string field resolve against the
 * script's invocation args ([[BrowserScript.parameters]] declares the
 * shape) at replay time. `SaveHtml(name, ...)` and structural-query
 * outputs bind under `${outputs.name}` for downstream steps.
 */
enum BrowserStep derives RW {

  /** Navigate to `url`. The `url` may contain `${arg}` placeholders. */
  case Navigate(url: String, waitForLoadSeconds: Int = 15)

  /** Click the first element matching `selector`. */
  case Click(selector: String)

  /** Type `value` into the element matched by `selector`. `value` may
    * contain `${arg}` placeholders. */
  case Type(selector: String, value: String, clearFirst: Boolean = true)

  /** Scroll the page. `direction` is `"up"` / `"down"`; `amount` is
    * `"page"` / `"top"` / `"bottom"`. */
  case Scroll(direction: String = "down", amount: String = "page")

  /** Capture a screenshot, persist it via storage, attach to the
    * `BrowserState`. */
  case Screenshot(waitSeconds: Int = 2)

  /** Persist the current page's HTML and bind the resulting
    * `htmlFileId` under `outputs.<name>`. Subsequent
    * `XPathQuery(htmlRef = "${outputs.<name>}", …)` /
    * `TextSearch(htmlRef = "${outputs.<name>}", …)` steps reference
    * it by string. */
  case SaveHtml(name: String)

  /** Run an XPath query against a saved HTML file. `htmlRef` resolves
    * to the saved file id (typically `"${outputs.<saveHtmlName>}"`).
    * The matched-nodes JSON is bound under `outputs.<name>`. */
  case XPathQuery(htmlRef: String,
                  xpath: String,
                  name: String,
                  maxResults: Int = 20,
                  includeOuterHtml: Boolean = false)

  /** Substring-search a saved HTML file's visible text. `htmlRef`
    * resolves to the saved file id (typically
    * `"${outputs.<saveHtmlName>}"`). The matches JSON is bound under
    * `outputs.<name>`. */
  case TextSearch(htmlRef: String,
                  query: String,
                  name: String,
                  contextChars: Int = 120,
                  maxResults: Int = 20,
                  caseSensitive: Boolean = false)

  /** Pause the script until `jsExpression` evaluates to truthy in
    * the page (or `timeoutSeconds` elapses). Used to wait for AJAX
    * content to render before proceeding. */
  case WaitForCondition(jsExpression: String, timeoutSeconds: Int = 10)
}
