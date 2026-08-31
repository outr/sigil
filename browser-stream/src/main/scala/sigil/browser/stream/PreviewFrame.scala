package sigil.browser.stream

import fabric.rw.*
import robobrowser.event.ScreencastFrameMetadata

/**
 * One frame of a CDP-screencast preview stream — the fallback rung of
 * [[StreamBrowserSigil.previewStreamFor]]'s ladder.
 *
 * `dataBase64` is the encoded image exactly as Chrome produced it
 * (JPEG by default); `metadata` carries the page scroll offset and the
 * device viewport the frame was rendered at, which a consumer needs to
 * map viewer coordinates back onto the page when forwarding input.
 * `sequence` is monotonic per session, so a consumer that sheds frames
 * on its own transport can tell how many it skipped.
 *
 * Chrome stalls a screencast after a handful of un-acked frames;
 * [[robobrowser.Screencast]] acks each frame as it arrives, before this
 * record is ever built, so a slow consumer degrades the preview's frame
 * rate rather than wedging the stream.
 */
final case class PreviewFrame(dataBase64: String,
                              metadata: ScreencastFrameMetadata,
                              sequence: Long)
  derives RW
