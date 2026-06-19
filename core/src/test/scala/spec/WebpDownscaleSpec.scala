package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.image.ImageDownscale

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Sigil #401 — `ImageDownscale.resize` silently no-op'd on webp because stock
 * `javax.imageio` has no webp reader, so a tall webp screenshot sailed past the
 * #400 many-image clamp and 400'd the request. With the TwelveMonkeys webp
 * reader on the classpath it decodes; because there's no webp WRITER, the
 * resized image is re-encoded to PNG and the reported media type changes to
 * match (a webp-labelled PNG would itself be rejected).
 */
class WebpDownscaleSpec extends AnyWordSpec with Matchers {

  private val tallWebp: Array[Byte] = {
    val in = getClass.getResourceAsStream("/images/tall.webp")
    try in.readAllBytes() finally in.close()
  }

  private def dims(bytes: Array[Byte]): (Int, Int) = {
    val img = ImageIO.read(new ByteArrayInputStream(bytes))
    (img.getWidth, img.getHeight)
  }

  "ImageDownscale on webp (#401)" should {
    "have a webp reader on the classpath" in {
      ImageIO.getImageReadersByMIMEType("image/webp").hasNext shouldBe true
      // Sanity: the fixture decodes at its declared size.
      dims(tallWebp) shouldBe (1280, 4380)
    }

    "downscale a tall webp to the per-edge cap and re-encode it as PNG" in {
      val r = ImageDownscale.resizeTyped(tallWebp, maxPixels = 0L, mediaType = "image/webp", maxEdge = ImageDownscale.ManyImageMaxEdge)
      // It actually shrank (was 4380 tall, cap 2000).
      val (w, h) = dims(r.bytes)
      withClue(s"resized to ${w}x${h} as ${r.mediaType}: ") {
        h should be <= ImageDownscale.ManyImageMaxEdge
        w should be <= ImageDownscale.ManyImageMaxEdge
      }
      // No webp writer → re-encoded to PNG, and the reported media type matches
      // the bytes (not the input webp).
      r.mediaType shouldBe "image/png"
      ImageIO.getImageReaders(ImageIO.createImageInputStream(new ByteArrayInputStream(r.bytes))).next().getFormatName.toLowerCase should include("png")
    }

    "leave a within-budget webp untouched (same bytes, same media type)" in {
      val r = ImageDownscale.resizeTyped(tallWebp, maxPixels = 0L, mediaType = "image/webp", maxEdge = ImageDownscale.MaxEdge)
      // 4380 ≤ 8000 — no resize, so the original webp bytes pass through as-is.
      (r.bytes eq tallWebp) shouldBe true
      r.mediaType shouldBe "image/webp"
    }

    "fall through (un-resized) and keep the media type for an undecodable blob" in {
      val junk = "not an image".getBytes("UTF-8")
      val r = ImageDownscale.resizeTyped(junk, maxPixels = 0L, mediaType = "image/png", maxEdge = ImageDownscale.ManyImageMaxEdge)
      (r.bytes eq junk) shouldBe true
      r.mediaType shouldBe "image/png"
    }
  }
}
