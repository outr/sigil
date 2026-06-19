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

  /** Resize `bytes` so its total pixel count is at most `maxPixels` AND
    * neither dimension exceeds `maxEdge`, preserving aspect ratio. Returns
    * the original bytes unchanged when the image is already within both
    * budgets, can't be decoded, or can't be re-encoded in `format`. NEVER
    * upscales — the applied scale is the min of two factors each clamped to
    * 1.0, so a sub-budget image passes through untouched. Best-effort: any
    * failure falls through to the original bytes rather than throwing on the
    * wire path. */
  def resize(bytes: Array[Byte], maxPixels: Long, format: String = "png", maxEdge: Int = MaxEdge): Array[Byte] =
    scala.util.Try {
      val src = ImageIO.read(new ByteArrayInputStream(bytes))
      if (src == null) bytes
      else {
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
        if (scale >= 1.0) bytes
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
          val fmt = formatFor(format)
          if (ImageIO.write(dst, fmt, baos)) baos.toByteArray else bytes
        }
      }
    }.getOrElse(bytes)

  /** Map a media type or extension to an `ImageIO` writer name. */
  private def formatFor(format: String): String = {
    val f = format.toLowerCase
    if (f.contains("png")) "png"
    else if (f.contains("jpeg") || f.contains("jpg")) "jpeg"
    else if (f.contains("gif")) "gif"
    else if (f.contains("bmp")) "bmp"
    else "png"
  }
}
