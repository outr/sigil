package sigil.browser

import fabric.rw.*

/**
 * Vertical scroll direction for a [[BrowserStep.Scroll]] step. Only
 * meaningful when [[ScrollAmount.Page]] is the amount — `Top` and
 * `Bottom` jump to an absolute position regardless of direction.
 */
enum ScrollDirection derives RW {
  case Up
  case Down
}
