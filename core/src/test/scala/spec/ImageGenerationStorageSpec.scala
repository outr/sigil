package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings,
  Instructions, Provider, ProviderCall, ProviderEvent, ProviderImage, ProviderType, StopReason
}
import sigil.signal.{EventState, ImageDelta, Signal}
import sigil.storage.{StoredFile, StoredFileCategory}
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Image-generation results must be persisted as stored files and
 * referenced by URL — never inlined into conversation history as a
 * multi-megabyte `data:` URL. A hosted URL the provider returned
 * passes through unchanged; inline base64 bytes are stored, with
 * partial previews kept on a short TTL and the final image persisted.
 */
class ImageGenerationStorageSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "image-model")

  /** A real 1x1 transparent PNG, base64-encoded. */
  private val tinyPng: String =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNgAAIAAAUAAen63NgAAAAASUVORK5CYII="

  private class ImageProvider(events: List[ProviderEvent]) extends Provider {
    override def `type`: ProviderType = ProviderType.OpenAI
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = Stream.emits(events)
  }

  private def run(events: List[ProviderEvent]): Task[List[Signal]] = {
    val convId = Conversation.id(s"img-store-${rapid.Unique()}")
    val conv = Conversation(topics = TestTopicStack, _id = convId)
    val request = ConversationRequest(
      conversationId = convId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = convId),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      previousTopics = Nil,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      chain = List(TestUser, TestAgent),
      tools = CoreTools.all.toVector
    )
    TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
      .flatMap(_ => Orchestrator.process(TestSigil, new ImageProvider(events), request, conv)
        // Publish each signal as it streams so events exist before the
        // orchestrator's termination-guarantee block runs — mirrors the
        // production agent loop.
        .flatMap(s => Stream.force(TestSigil.publish(s).map(_ => Stream.emits(List(s)))))
        .toList)
  }

  private def imageUrls(signals: List[Signal]): List[spice.net.URL] =
    signals.collect {
      case m: Message    => m.content.collectFirst { case i: ResponseContent.Image => i.url }
      case d: ImageDelta => Some(d.url)
    }.flatten

  "image-generation result storage" should {

    "persist an inline image and reference it by a storage URL, never a data URL" in {
      run(List(
        ProviderEvent.ImageGenerationComplete(CallId("img-1"), ProviderImage.Inline(tinyPng, "image/png")),
        ProviderEvent.Done(StopReason.Complete)
      )).map { signals =>
        val urls = imageUrls(signals)
        urls should have size 1
        urls.head.toString should startWith("sigil://storage/")
        urls.head.toString should not include "data:"
      }
    }

    "pass a hosted image URL through unchanged" in {
      val hosted = spice.net.URL.get("https://example.com/generated.png").toOption.get
      run(List(
        ProviderEvent.ImageGenerationComplete(CallId("img-2"), ProviderImage.Hosted(hosted)),
        ProviderEvent.Done(StopReason.Complete)
      )).map { signals =>
        val urls = imageUrls(signals)
        urls should have size 1
        urls.head shouldBe hosted
      }
    }

    "store a partial preview with a TTL and the final image persistently" in {
      val callId = CallId("img-3")
      run(List(
        ProviderEvent.ImageGenerationPartial(callId, ProviderImage.Inline(tinyPng, "image/png")),
        ProviderEvent.ImageGenerationComplete(callId, ProviderImage.Inline(tinyPng, "image/png")),
        ProviderEvent.Done(StopReason.Complete)
      )).flatMap { signals =>
        val urls = imageUrls(signals)
        urls should have size 2
        val ids = urls.map(u => Id[StoredFile](u.toString.stripPrefix("sigil://storage/")))
        Task.sequence(ids.map(id => TestSigil.withDB(_.storedFiles.transaction(_.get(id))))).map { fetched =>
          val files = fetched.flatten
          files should have size 2
          val partial = files.find(_.category == StoredFileCategory.ExternalizedContent)
          val finalImage = files.find(_.category == StoredFileCategory.UserAttachment)
          partial.flatMap(_.expiresAt) should not be empty
          finalImage.map(_.expiresAt) shouldBe Some(None)
        }
      }
    }

    "settle a partial-only image message at turn end when no completion event arrives" in {
      run(List(
        ProviderEvent.ImageGenerationPartial(CallId("img-orphan"), ProviderImage.Inline(tinyPng, "image/png")),
        ProviderEvent.Done(StopReason.Complete)
      )).flatMap { signals =>
        val imageMessage = signals.collectFirst {
          case m: Message if m.content.exists { case _: ResponseContent.Image => true; case _ => false } => m
        }
        imageMessage should not be empty
        // The settle is published by the orchestrator's termination-guarantee
        // block, not emitted into the returned stream — assert on persisted state.
        TestSigil.withDB(_.events.transaction(_.get(imageMessage.get._id))).map { persisted =>
          withClue(s"signals: ${signals.map(_.getClass.getSimpleName).mkString(", ")}") {
            persisted.map(_.state) shouldBe Some(EventState.Complete)
          }
        }
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
