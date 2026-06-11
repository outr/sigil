package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.compression.TokenEstimator
import sigil.image.ImageDownscale
import sigil.tool.ImageQuality
import spice.net.*

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Sigil #382 — quality tier plumbing: the URL-param carrier across the
 * persisted frame boundary, the provider-detail mapping, server-side
 * downscaling, and per-tier token accounting.
 */
class ImageQualitySpec extends AnyWordSpec with Matchers {

  private def pngBytes(w: Int, h: Int): Array[Byte] = {
    val img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.fillRect(0, 0, w, h)
    g.dispose()
    val baos = new ByteArrayOutputStream()
    ImageIO.write(img, "png", baos)
    baos.toByteArray
  }

  private def dimsOf(bytes: Array[Byte]): (Int, Int) = {
    val img = ImageIO.read(new java.io.ByteArrayInputStream(bytes))
    (img.getWidth, img.getHeight)
  }

  "ImageQuality" should {
    "round-trip through a URL's _q param" in {
      val base = url"https://example.invalid/img.png"
      ImageQuality.values.foreach { q =>
        val stamped = ImageQuality.stamp(base, q)
        ImageQuality.fromUrl(stamped) shouldBe q
        ImageQuality.strip(stamped) shouldBe base
      }
    }

    "default to Low when no _q param is present" in {
      ImageQuality.fromUrl(url"https://example.invalid/img.png") shouldBe ImageQuality.Low
    }

    "parse names case-insensitively" in {
      ImageQuality.of("high") shouldBe Some(ImageQuality.High)
      ImageQuality.of("THUMBNAIL") shouldBe Some(ImageQuality.Thumbnail)
      ImageQuality.of("bogus") shouldBe None
    }

    "map to OpenAI detail (only High is high)" in {
      ImageQuality.Thumbnail.openAIDetail shouldBe "low"
      ImageQuality.Low.openAIDetail shouldBe "low"
      ImageQuality.High.openAIDetail shouldBe "high"
    }

    "expose ascending long-edge caps" in {
      ImageQuality.Thumbnail.maxLongEdge shouldBe 128
      ImageQuality.Low.maxLongEdge shouldBe 512
      ImageQuality.High.maxLongEdge shouldBe 1568
    }

    "expose an area budget of maxLongEdge squared (#383)" in {
      ImageQuality.Thumbnail.maxPixels shouldBe 128L * 128L
      ImageQuality.Low.maxPixels shouldBe 512L * 512L
      ImageQuality.High.maxPixels shouldBe 1568L * 1568L
    }
  }

  "ImageDownscale.resize" should {
    "downscale to within the pixel budget, preserving aspect ratio" in {
      val budget = ImageQuality.Low.maxPixels
      val (w, h) = dimsOf(ImageDownscale.resize(pngBytes(2000, 1000), budget))
      (w.toLong * h.toLong) should be <= budget
      // 2:1 aspect preserved.
      (w.toDouble / h.toDouble) shouldBe (2.0 +- 0.05)
    }

    "keep a tall full-page screenshot legible — no long-edge crush (#383)" in {
      // 1280×10000 at High: a long-edge cap produced a ~201 px sliver and
      // manufactured a re-screenshot loop. The area budget keeps a usable
      // width (~561 px).
      val budget = ImageQuality.High.maxPixels
      val (w, h) = dimsOf(ImageDownscale.resize(pngBytes(1280, 10000), budget))
      (w.toLong * h.toLong) should be <= budget
      w should be >= 500
      // aspect preserved (still very tall).
      h should be > w
    }

    "never upscale an already-small image" in {
      val original = pngBytes(64, 48)
      val result = ImageDownscale.resize(original, ImageQuality.Low.maxPixels)
      dimsOf(result) shouldBe (64, 48)
    }

    "return original bytes for undecodable input" in {
      val junk = "not an image".getBytes("UTF-8")
      ImageDownscale.resize(junk, ImageQuality.Low.maxPixels) shouldBe junk
    }
  }

  "TokenEstimator.imageTokens" should {
    "scale with the URL-stamped quality tier" in {
      val base = url"https://example.invalid/img.png"
      TokenEstimator.imageTokens(ImageQuality.stamp(base, ImageQuality.Thumbnail)) shouldBe 22
      TokenEstimator.imageTokens(ImageQuality.stamp(base, ImageQuality.Low)) shouldBe 350
      TokenEstimator.imageTokens(ImageQuality.stamp(base, ImageQuality.High)) shouldBe 1600
      // Unstamped defaults to Low.
      TokenEstimator.imageTokens(base) shouldBe 350
    }
  }
}
