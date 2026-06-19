package sigil.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.imageio.ImageIO

/**
 * Sigil #382 — server-side image downscaling, run before an image is
 * base64-encoded for the wire (or before a URL is emitted). No image is
 * ever shipped at original resolution: a quality tier picks a total-pixel
 * budget and everything is clamped to it, aspect ratio preserved. Uses
 * the JDK's `javax.imageio` (no new dependency).
 *
 * The token cost of an image is capped by the provider tokenizer
 * (~1,600 tokens for Claude) regardless of byte size, so the win here is
 * payload bytes + a stable cached prefix, not token count.
 *
 * Budgeting by AREA, not long edge (#383): a long-edge cap crushes a
 * tall full-page screenshot (e.g. 1280×10000) into an illegible ~66–201
 * px-wide sliver, which manufactured a re-screenshot loop. An area
 * budget keeps a usable width — the same capture at the `High` budget
 * reduces to ~561×4380 instead.
 */
object ImageDownscale {

  /** Hard per-dimension ceiling enforced regardless of the area budget.
    * Anthropic rejects (HTTP 400) any image whose width or height exceeds
    * 8000 px; OpenAI and Gemini allow at least this much, so a single
    * universal cap at the seam never ships an over-limit edge to any
    * provider. The area budget alone can't guarantee this: an extreme
    * aspect ratio (a tall full-page screenshot) can satisfy the total-pixel
    * budget while one edge still blows past 8000 px. */
  val MaxEdge: Int = 8000

  /** Per-edge ceiling Anthropic enforces for a "many-image" request — once a
    * request carries more than [[ManyImageThreshold]] images, the per-edge
    * limit drops from [[MaxEdge]] (8000) to 2000 px and the WHOLE request 400s
    * if any image exceeds it. The per-image downscale can't see the
    * request-wide count, so the count-aware clamp lives at the provider wire
    * seam (#400). 2000 forces the "tall screenshot → sliver" tradeoff #383
    * avoided, but the API leaves no choice for many-image requests. */
  val ManyImageMaxEdge: Int = 2000

  /** Image count above which [[ManyImageMaxEdge]] applies (Anthropic's
    * many-image threshold). At or below it, [[MaxEdge]] stands. */
  val ManyImageThreshold: Int = 20

  /** A resized image plus the media type its bytes are actually in. The media
    * type can DIFFER from the input's: an input format `ImageIO` can read but
    * not write (webp — TwelveMonkeys ships a reader, no writer) is re-encoded
    * to PNG, so the wire layer must carry the new media type or it ships
    * PNG bytes mislabelled as webp. `changed` is true only when the bytes were
    * actually re-encoded (callers detect a no-op via `bytes eq input`). */
  final case class Resized(bytes: Array[Byte], mediaType: String)

  /** Resize `bytes` so its total pixel count is at most `maxPixels` AND
    * neither dimension exceeds `maxEdge`, preserving aspect ratio. Returns the
    * original bytes (the SAME array reference) unchanged when the image is
    * already within both budgets. NEVER upscales. See [[resizeTyped]] for the
    * media-type-aware variant the wire seam uses; this is the bytes-only
    * convenience for callers that don't re-label the result. */
  def resize(bytes: Array[Byte], maxPixels: Long, format: String = "png", maxEdge: Int = MaxEdge): Array[Byte] =
    resizeTyped(bytes, maxPixels, format, maxEdge).bytes

  /** Resize, reporting the media type the result is encoded in. On a no-op
    * (already within budget) returns the original bytes + `mediaType`. On a
    * real resize, re-encodes via [[formatFor]] (webp → PNG, since there's no
    * webp writer) and reports the matching media type. Fails LOUD — an
    * undecodable image or a write that can't produce the format is `scribe.warn`'d
    * rather than silently returning the oversized original, so a request-killing
    * 400 downstream leaves a breadcrumb. */
  def resizeTyped(bytes: Array[Byte], maxPixels: Long, mediaType: String, maxEdge: Int = MaxEdge): Resized = {
    val original = Resized(bytes, mediaType)
    scala.util.Try {
      val src = ImageIO.read(new ByteArrayInputStream(bytes))
      if (src == null) {
        scribe.warn(s"ImageDownscale: no ImageIO reader for media type '$mediaType' (${bytes.length} bytes) — " +
          "image passes through un-resized and may exceed the provider's per-edge cap. Add a reader plugin for this format.")
        original
      } else {
        val (w, h) = (src.getWidth, src.getHeight)
        val pixels = w.toLong * h.toLong
        // Area factor: shrink so w·h ≤ maxPixels (1.0 when already within, or
        // when the budget is non-positive — area unconstrained).
        val areaScale = if (maxPixels > 0 && pixels > maxPixels) math.sqrt(maxPixels.toDouble / pixels.toDouble) else 1.0
        // Edge factor: shrink so max(w, h) ≤ maxEdge (1.0 when already within,
        // or when the cap is non-positive — edge unconstrained).
        val longest = math.max(w, h)
        val edgeScale = if (maxEdge > 0 && longest > maxEdge) maxEdge.toDouble / longest.toDouble else 1.0
        // The more aggressive of the two. Both are ≤ 1.0, so this never upscales.
        val scale = math.min(areaScale, edgeScale)
        if (scale >= 1.0) original
        else {
          // Floor (not round) so the result never exceeds either budget:
          // floor(w·s)·floor(h·s) ≤ w·h·s² ≤ maxPixels, and floor(longest·s) ≤ maxEdge.
          val nw = math.max(1, math.floor(w * scale).toInt)
          val nh = math.max(1, math.floor(h * scale).toInt)
          val hasAlpha = src.getColorModel.hasAlpha
          val dst = new BufferedImage(nw, nh, if (hasAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
          val g = dst.createGraphics()
          g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
          g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
          g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
          g.drawImage(src, 0, 0, nw, nh, null)
          g.dispose()
          val baos = new ByteArrayOutputStream()
          val fmt = formatFor(mediaType)
          if (ImageIO.write(dst, fmt, baos)) Resized(baos.toByteArray, mediaTypeFor(fmt))
          else {
            scribe.warn(s"ImageDownscale: no ImageIO writer for format '$fmt' (from media type '$mediaType') — " +
              s"image passes through un-resized at ${w}x${h} and may exceed the provider's per-edge cap.")
            original
          }
        }
      }
    }.recover { case t =>
      scribe.warn(s"ImageDownscale: failed to resize image (media type '$mediaType'): ${t.getMessage}", t)
      original
    }.get
  }

  /** Map a media type or extension to an `ImageIO` writer name. webp has a
    * reader but no writer (TwelveMonkeys), so it re-encodes to PNG (lossless). */
  private def formatFor(format: String): String = {
    val f = format.toLowerCase
    if (f.contains("png")) "png"
    else if (f.contains("jpeg") || f.contains("jpg")) "jpeg"
    else if (f.contains("gif")) "gif"
    else if (f.contains("bmp")) "bmp"
    else "png"
  }

  /** The media type produced by an `ImageIO` writer name (the inverse of
    * [[formatFor]] for the formats it targets). */
  private def mediaTypeFor(fmt: String): String = fmt match {
    case "jpeg" => "image/jpeg"
    case "gif"  => "image/gif"
    case "bmp"  => "image/bmp"
    case _      => "image/png"
  }
}
