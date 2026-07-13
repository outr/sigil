package sigil.provider

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.GlobalSpace
import sigil.db.Model
import spec.TestSigil
import spice.http.HttpRequest

/**
 * `normalizeStoredImages` must materialize internally-stored images
 * into inline [[MessageContent.ImageBytes]] for the wire — the default
 * local-storage URL is not reachable by a provider's servers — while
 * leaving genuinely public image URLs alone.
 */
class MessageContentImageNormalizationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** A real 1x1 transparent PNG. */
  private val tinyPng: Array[Byte] = java.util.Base64.getDecoder.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNgAAIAAAUAAen63NgAAAAASUVORK5CYII="
  )

  private object FakeProvider extends Provider {
    override def `type`: ProviderType = ProviderType.OpenAI
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
  }

  private def callWith(content: MessageContent*): ProviderCall =
    ProviderCall(
      model = TestSigil.testModel(Model.id("test", "vision-model")),
      system = "",
      messages = Vector(ProviderMessage.User(content.toVector)),
      tools = Vector.empty,
      builtInTools = Set.empty,
      toolChoice = ToolChoice.Auto,
      generationSettings = GenerationSettings()
    )

  private def imageContents(call: ProviderCall): Vector[MessageContent] =
    call.messages.collect { case ProviderMessage.User(c) => c }.flatten

  "normalizeStoredImages" should {

    "rewrite an internally-stored image to inline ImageBytes" in {
      TestSigil.storeBytes(GlobalSpace, tinyPng, "image/png").flatMap { stored =>
        FakeProvider.normalizeStoredImages(callWith(MessageContent.Image(TestSigil.storageUrl(stored)))).map { normalized =>
          imageContents(normalized) match {
            case Vector(MessageContent.ImageBytes(mediaType, base64, _, _)) =>
              mediaType shouldBe "image/png"
              java.util.Base64.getDecoder.decode(base64) shouldBe tinyPng
            case other => fail(s"expected ImageBytes, got $other")
          }
        }
      }
    }

    "fetch + downscale an EXTERNAL image URL to inline ImageBytes (sigil #393)" in {
      val publicUrl = spice.net.URL.get("https://cdn.example.com/pic.png").toOption.get
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)
      TestSigil.onFetchExternalImage { _ => Task { calls.incrementAndGet(); Some((tinyPng, "image/png")) } }
      val act = for {
        a <- FakeProvider.normalizeStoredImages(callWith(MessageContent.Image(publicUrl)))
        // Second call with the same url+quality must hit the process cache —
        // no second fetch (stable base64 keeps provider prompt-caching warm).
        b <- FakeProvider.normalizeStoredImages(callWith(MessageContent.Image(publicUrl)))
      } yield (a, b)
      act.map { case (a, b) =>
        imageContents(a) match {
          case Vector(MessageContent.ImageBytes(mt, base64, _, _)) =>
            mt shouldBe "image/png"
            base64.nonEmpty shouldBe true
          case other => fail(s"expected ImageBytes, got $other")
        }
        imageContents(b).head.isInstanceOf[MessageContent.ImageBytes] shouldBe true
        calls.get() shouldBe 1 // fetched once, then cached
        TestSigil.resetFetchExternalImage()
        succeed
      }
    }

    "replace an unfetchable external image with an [image unavailable] marker (sigil #393/#417)" in {
      val deadUrl = spice.net.URL.get("https://cdn.example.com/too-big.png").toOption.get
      TestSigil.onFetchExternalImage { _ => Task.pure(None) }
      FakeProvider.normalizeStoredImages(
        callWith(MessageContent.Text("Store file gid://… — a hero"), MessageContent.Image(deadUrl, altText = Some("hero banner")))
      ).map { normalized =>
        // No image block ships (an empty one would 400 the whole request);
        // the caption survives in the marker so the model knows the image
        // isn't visually present.
        imageContents(normalized) shouldBe Vector(
          MessageContent.Text("Store file gid://… — a hero"),
          MessageContent.Text("[image unavailable: hero banner]")
        )
        TestSigil.resetFetchExternalImage()
        succeed
      }
    }

    "not cache an unavailable external image — a later successful fetch recovers it" in {
      val flaky = spice.net.URL.get("https://cdn.example.com/flaky.png").toOption.get
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)
      TestSigil.onFetchExternalImage { _ =>
        Task {
          if (calls.incrementAndGet() == 1) None else Some((tinyPng, "image/png"))
        }
      }
      val act = for {
        first  <- FakeProvider.normalizeStoredImages(callWith(MessageContent.Image(flaky)))
        second <- FakeProvider.normalizeStoredImages(callWith(MessageContent.Image(flaky)))
      } yield (first, second)
      act.map { case (first, second) =>
        imageContents(first) shouldBe Vector(MessageContent.Text("[image unavailable]"))
        imageContents(second).head.isInstanceOf[MessageContent.ImageBytes] shouldBe true
        TestSigil.resetFetchExternalImage()
        succeed
      }
    }

    "replace an unresolvable sigil://storage URL with a marker — the provider can never fetch it (sigil #417)" in {
      val dangling = spice.net.URL.get("sigil://storage/no-such-file",
        tldValidation = spice.net.TLDValidation.Off).toOption.get
      FakeProvider.normalizeStoredImages(callWith(MessageContent.Image(dangling, altText = Some("lost render")))).map { normalized =>
        imageContents(normalized) shouldBe Vector(MessageContent.Text("[image unavailable: lost render]"))
      }
    }

    "route an http storage-shaped URL with no local row through the external fetch" in {
      val foreign = spice.net.URL.get("https://cdn.example.com/storage/some-public-file").toOption.get
      TestSigil.onFetchExternalImage { _ => Task.pure(Some((tinyPng, "image/png"))) }
      FakeProvider.normalizeStoredImages(callWith(MessageContent.Image(foreign))).map { normalized =>
        imageContents(normalized).head.isInstanceOf[MessageContent.ImageBytes] shouldBe true
        TestSigil.resetFetchExternalImage()
        succeed
      }
    }

    "replace a ZERO-BYTE stored image with a marker instead of an empty image block (sigil #417 field shape)" in {
      // The bricking mechanism: a flaky capture stored an empty webp; every
      // request re-rendering the frame shipped base64 "" and the provider
      // rejected the ENTIRE request ("image cannot be empty") — forever.
      TestSigil.storeBytes(GlobalSpace, Array.emptyByteArray, "image/webp").flatMap { stored =>
        FakeProvider.normalizeStoredImages(
          callWith(MessageContent.Image(TestSigil.storageUrl(stored), altText = Some("theme preview")))
        ).map { normalized =>
          imageContents(normalized) shouldBe Vector(MessageContent.Text("[image unavailable: theme preview]"))
        }
      }
    }

    "replace a stored image whose blob is gone with a marker" in {
      TestSigil.storeBytes(GlobalSpace, tinyPng, "image/png").flatMap { stored =>
        TestSigil.storageProvider.delete(stored.path).flatMap { _ =>
          FakeProvider.normalizeStoredImages(
            callWith(MessageContent.Image(TestSigil.storageUrl(stored)))
          ).map { normalized =>
            imageContents(normalized) shouldBe Vector(MessageContent.Text("[image unavailable]"))
          }
        }
      }
    }

    "replace an empty inline ImageBytes with a marker" in {
      FakeProvider.normalizeStoredImages(
        callWith(MessageContent.ImageBytes("image/webp", "", altText = Some("inline capture")))
      ).map { normalized =>
        imageContents(normalized) shouldBe Vector(MessageContent.Text("[image unavailable: inline capture]"))
      }
    }

    "leave text content untouched" in {
      FakeProvider.normalizeStoredImages(callWith(MessageContent.Text("hello"))).map { normalized =>
        imageContents(normalized) shouldBe Vector(MessageContent.Text("hello"))
      }
    }

    // A conversation that already captured an oversized inline image (a tall
    // full-page screenshot whose height exceeds Anthropic's 8000 px cap) must
    // self-heal at wire-build time: the persisted event keeps the original
    // bytes, but every request the provider builds is re-clamped so the image
    // can't 400 the model turn after turn.
    "clamp an oversized inline ImageBytes so neither dimension exceeds the provider edge cap" in {
      def pngBytes(w: Int, h: Int): Array[Byte] = {
        val img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val baos = new java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", baos)
        baos.toByteArray
      }
      def dims(b64: String): (Int, Int) = {
        val img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder.decode(b64)))
        (img.getWidth, img.getHeight)
      }
      val tall = java.util.Base64.getEncoder.encodeToString(pngBytes(200, 9000))
      FakeProvider.normalizeStoredImages(callWith(MessageContent.ImageBytes("image/png", tall))).map { normalized =>
        imageContents(normalized) match {
          case Vector(MessageContent.ImageBytes(_, base64, _, _)) =>
            val (w, h) = dims(base64)
            withClue(s"clamped to ${w}x${h}: ") {
              h should be <= _root_.sigil.image.ImageDownscale.MaxEdge
              w should be <= _root_.sigil.image.ImageDownscale.MaxEdge
            }
          case other => fail(s"expected ImageBytes, got $other")
        }
      }
    }

    "leave an inline ImageBytes within the edge cap byte-for-byte unchanged" in {
      val b64 = java.util.Base64.getEncoder.encodeToString(tinyPng)
      FakeProvider.normalizeStoredImages(callWith(MessageContent.ImageBytes("image/png", b64))).map { normalized =>
        imageContents(normalized) match {
          case Vector(MessageContent.ImageBytes(_, base64, _, _)) => base64 shouldBe b64
          case other => fail(s"expected ImageBytes, got $other")
        }
      }
    }
  }

  // Sigil #400 — a provider whose per-edge cap tightens for many-image
  // requests (Anthropic: 8000 px normally, 2000 px above ~20 images).
  private object ManyImageCapProvider extends Provider {
    override def `type`: ProviderType = ProviderType.Anthropic
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
    override protected def imageEdgeCapFor(imageCount: Int): Int =
      if (imageCount > _root_.sigil.image.ImageDownscale.ManyImageThreshold) _root_.sigil.image.ImageDownscale.ManyImageMaxEdge
      else _root_.sigil.image.ImageDownscale.MaxEdge
  }

  private def pngBytes(w: Int, h: Int): Array[Byte] = {
    val img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val baos = new java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(img, "png", baos)
    baos.toByteArray
  }
  private def dims(b64: String): (Int, Int) = {
    val img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder.decode(b64)))
    (img.getWidth, img.getHeight)
  }
  private def imageBytesOf(call: ProviderCall): Vector[MessageContent.ImageBytes] =
    imageContents(call).collect { case ib: MessageContent.ImageBytes => ib }

  "the many-image per-edge cap (Sigil #400)" should {
    // A tall full-page screenshot (1280×4380) is legal at 8000 but violates
    // Anthropic's 2000 px many-image limit. Below the threshold it stays;
    // above it, every image's long edge is clamped to 2000.
    def tall: MessageContent.ImageBytes =
      MessageContent.ImageBytes("image/png", java.util.Base64.getEncoder.encodeToString(pngBytes(1280, 4380)))
    def tiny: MessageContent.ImageBytes =
      MessageContent.ImageBytes("image/png", java.util.Base64.getEncoder.encodeToString(tinyPng))

    "leave a tall image alone when the request is within the many-image threshold" in {
      ManyImageCapProvider.normalizeStoredImages(callWith((tall +: Vector.fill(4)(tiny)): _*)).map { normalized =>
        val (_, h) = dims(imageBytesOf(normalized).head.base64)
        // 4380 ≤ 8000 — untouched; legibility preserved for a few-image request.
        h shouldBe 4380
      }
    }

    "clamp every image's long edge to 2000 once the request crosses the many-image threshold" in {
      // 21 images (> 20): the many-image cap kicks in for the WHOLE request.
      val content = tall +: Vector.fill(20)(tiny)
      ManyImageCapProvider.normalizeStoredImages(callWith(content: _*)).map { normalized =>
        val images = imageBytesOf(normalized)
        images should have size 21
        images.foreach { ib =>
          val (w, h) = dims(ib.base64)
          withClue(s"image ${w}x${h} exceeds the 2000px many-image cap: ") {
            w should be <= _root_.sigil.image.ImageDownscale.ManyImageMaxEdge
            h should be <= _root_.sigil.image.ImageDownscale.ManyImageMaxEdge
          }
        }
        succeed
      }
    }

    "NOT apply the many-image cap for a provider that doesn't declare one" in {
      // FakeProvider (OpenAI) keeps the default 8000 cap regardless of count.
      val content = tall +: Vector.fill(20)(tiny)
      FakeProvider.normalizeStoredImages(callWith(content: _*)).map { normalized =>
        val (_, h) = dims(imageBytesOf(normalized).find(b => dims(b.base64)._2 > 100).get.base64)
        h shouldBe 4380
      }
    }
  }

  "invalid-request classification (sigil #417)" should {

    "detect a non-overflow invalid_request and extract the provider's concise message" in Task {
      val fieldError = new RuntimeException(
        """HTTP 400: {"type":"error","error":{"type":"invalid_request_error","message":"messages.140.content.1.image.source.base64: image cannot be empty"}}"""
      )
      Provider.isInvalidRequest(fieldError) shouldBe true
      Provider.invalidRequestDetail(fieldError) shouldBe
        Some("messages.140.content.1.image.source.base64: image cannot be empty")
    }

    "classify a context overflow as overflow, never as generic invalid_request" in Task {
      val overflow = new RuntimeException(
        """HTTP 400: {"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 200277 tokens > 200000 maximum"}}"""
      )
      Provider.isContextOverflow(overflow) shouldBe true
      Provider.isInvalidRequest(overflow) shouldBe false
    }

    "walk the cause chain for the invalid_request body" in Task {
      val inner = new RuntimeException(
        """{"type":"error","error":{"type":"invalid_request_error","message":"tools.3.custom.input_schema: JSON schema is invalid"}}"""
      )
      val outer = new RuntimeException("stream failed", inner)
      Provider.isInvalidRequest(outer) shouldBe true
      Provider.invalidRequestDetail(outer) shouldBe Some("tools.3.custom.input_schema: JSON schema is invalid")
    }

    "stay quiet for unrelated errors" in Task {
      Provider.isInvalidRequest(new RuntimeException("connection reset")) shouldBe false
      Provider.invalidRequestDetail(new RuntimeException("connection reset")) shouldBe None
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
