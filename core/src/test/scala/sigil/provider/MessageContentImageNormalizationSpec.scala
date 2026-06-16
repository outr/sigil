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

    "DROP an external image URL that can't be fetched — caption survives (sigil #393)" in {
      val deadUrl = spice.net.URL.get("https://cdn.example.com/too-big.png").toOption.get
      TestSigil.onFetchExternalImage { _ => Task.pure(None) }
      FakeProvider.normalizeStoredImages(
        callWith(MessageContent.Text("Store file gid://… — a hero"), MessageContent.Image(deadUrl))
      ).map { normalized =>
        // The unfetchable image is dropped; its caption Text block remains.
        imageContents(normalized) shouldBe Vector(MessageContent.Text("Store file gid://… — a hero"))
        TestSigil.resetFetchExternalImage()
        succeed
      }
    }

    "leave a storage-shaped URL whose id does not resolve untouched" in {
      val dangling = spice.net.URL.get("sigil://storage/no-such-file",
        tldValidation = spice.net.TLDValidation.Off).toOption.get
      FakeProvider.normalizeStoredImages(callWith(MessageContent.Image(dangling))).map { normalized =>
        imageContents(normalized) shouldBe Vector(MessageContent.Image(dangling))
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
              h should be <= sigil.image.ImageDownscale.MaxEdge
              w should be <= sigil.image.ImageDownscale.MaxEdge
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

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
