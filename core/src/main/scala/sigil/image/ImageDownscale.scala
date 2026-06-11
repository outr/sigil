package sigil.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import javax.imageio.ImageIO

/**
 * Sigil #382 — server-side image downscaling, run before an image is
 * base64-encoded for the wire (or before a URL is emitted). No image is
 * ever shipped at original resolution: a quality tier picks the target
 * long-edge and everything is clamped to it. Uses the JDK's
 * `javax.imageio` (no new dependency).
 *
 * The token cost of an image is capped by the provider tokenizer
 * (~1,600 tokens for Claude at 1568 px) regardless of byte size, so the
 * win here is payload bytes + a stable cached prefix, not token count.
 */
object ImageDownscale {

  /** Resize `bytes` so its long edge is at most `maxLongEdge` px,
    * preserving aspect ratio. Returns the original bytes unchanged when
    * the image is already within bounds, can't be decoded, or can't be
    * re-encoded in `format`. NEVER upscales. Best-effort: any failure
    * falls through to the original bytes rather than throwing on the
    * wire path. */
  def resize(bytes: Array[Byte], maxLongEdge: Int, format: String = "png"): Array[Byte] =
    scala.util.Try {
      val src = ImageIO.read(new ByteArrayInputStream(bytes))
      if (src == null) bytes
      else {
        val (w, h) = (src.getWidth, src.getHeight)
        val longEdge = math.max(w, h)
        if (longEdge <= maxLongEdge || maxLongEdge <= 0) bytes
        else {
          val scale = maxLongEdge.toDouble / longEdge
          val nw = math.max(1, math.round(w * scale).toInt)
          val nh = math.max(1, math.round(h * scale).toInt)
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
