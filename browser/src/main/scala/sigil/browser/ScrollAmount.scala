package sigil.browser

import fabric.rw.*

/**
 * Scroll magnitude for a [[BrowserStep.Scroll]] step.
 *
 *   - [[Page]] — scroll one viewport height in the step's
 *     [[ScrollDirection]].
 *   - [[Top]] — jump to the top of the document.
 *   - [[Bottom]] — jump to the bottom of the document.
 */
enum ScrollAmount derives RW {
  case Page
  case Top
  case Bottom
}
