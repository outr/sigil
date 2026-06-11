package sigil.tool

import fabric.rw.*
import spice.net.URL

/**
 * Sigil #382 — how much detail an image is delivered to the model with.
 * The producing tool (the only party that knows whether it's glancing or
 * scrutinizing) sets it per [[ImageToolOutput]]; the framework downscales
 * the bytes to [[maxLongEdge]] before encoding (and maps to a provider's
 * native `detail` flag where one exists). The original is preserved in
 * storage, so a re-view at a higher quality is a cheap, legitimate
 * escalation rather than a forced re-fetch.
 *
 *   - `Thumbnail` (128 px) — scanning a GRID to pick one image to open.
 *   - `Low` (512 px, the default) — a normal glance: layout, colour,
 *     composition. Mirrors OpenAI's `detail: low`.
 *   - `High` (1568 px, the Anthropic API cap) — scrutiny: read text,
 *     fine detail, compare similar images.
 */
enum ImageQuality derives RW {
  case Thumbnail
  case Low
  case High

  /** Target long-edge in pixels. The downscaler never upscales and
    * never exceeds this; `High` sits at the provider tokenizer cap so
    * larger originals are always reduced. */
  def maxLongEdge: Int = this match {
    case ImageQuality.Thumbnail => 128
    case ImageQuality.Low       => 512
    case ImageQuality.High      => 1568
  }

  /** OpenAI's native image `detail` flag (`low` | `high`). Anthropic and
    * Gemini have no equivalent — our server-side downscale is the control
    * there. `Thumbnail`/`Low` map to `low`; `High` to `high`. */
  def openAIDetail: String = this match {
    case ImageQuality.High => "high"
    case _                 => "low"
  }
}

object ImageQuality {

  /** Internal query param that carries quality across the persisted
    * `frame.images: List[URL]` boundary, whose element type can't change
    * without re-introducing a frame-decode migration (#374/#380).
    * [[sigil.conversation.FrameBuilder]] stamps it when lifting an
    * [[ImageToolOutput]]'s URL; the provider parses it back into the
    * typed wire content and strips it before resolving the StoredFile. */
  val UrlParam: String = "_q"

  /** Parse a quality by name (case-insensitive); `None` if unknown. */
  def of(name: String): Option[ImageQuality] =
    values.find(_.toString.equalsIgnoreCase(name))

  /** Stamp the quality onto a URL as the [[UrlParam]]. */
  def stamp(url: URL, quality: ImageQuality): URL = url.withParam(UrlParam, quality.toString)

  /** Read the quality off a URL's [[UrlParam]] (default [[Low]]). */
  def fromUrl(url: URL): ImageQuality = url.param(UrlParam).flatMap(of).getOrElse(ImageQuality.Low)

  /** Drop the [[UrlParam]] — e.g. before resolving a StoredFile id. */
  def strip(url: URL): URL = url.removeParam(UrlParam)
}
