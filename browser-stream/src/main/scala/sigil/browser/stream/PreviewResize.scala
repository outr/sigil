package sigil.browser.stream

import rapid.Task

/**
 * Applies a new render size to a live preview session.
 *
 * Both rungs of the ladder can be resized, by different means — a WebRTC
 * session reconfigures its live pipeline without renegotiating, a
 * screencast re-lays the page out and restarts capture behind the same
 * frame stream — so [[StreamBrowserSigil.resizePreview]] holds this
 * rather than a session kind.
 */
trait PreviewResize {

  /**
   * Render and capture at `width` x `height` from now on.
   */
  def apply(width: Int, height: Int): Task[Unit]
}
