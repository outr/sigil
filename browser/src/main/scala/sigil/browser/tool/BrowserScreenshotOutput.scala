package sigil.browser.tool

import fabric.rw.*
import sigil.tool.ToolOutput

/**
 * Typed result of [[BrowserScreenshotTool]]. `fileId` is the
 * persisted [[sigil.storage.StoredFile]] id of the captured PNG;
 * `url` resolves through the framework's storage route so a UI can
 * fetch the image without knowing the storage backend. The capture
 * is also surfaced live to subscribers via the
 * [[sigil.browser.BrowserStateDelta]] published during execution.
 */
case class BrowserScreenshotOutput(fileId: String,
                                   url: String,
                                   altText: String)
  extends ToolOutput derives RW
