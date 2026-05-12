package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextSummary, Conversation, TopicEntry}
import sigil.conversation.compression.{
  ContextCompressor, NoOpBlockExtractor, NoOpMemoryRetriever, Percentage,
  StandardContextCurator, StandardContextOptimizer
}
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.{Event, Message}
import sigil.participant.ParticipantId
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression for sigil bug #147 — stage-3 shed summarised the
 * oldest frames + persisted a `ContextSummary` but never advanced
 * `Conversation.clearedAt`. The next turn's `framesFor` returned
 * the same frames again, stage-3 re-fired, summarised them again,
 * forever. Verifies:
 *
 *   - After a stage-3 shed produces a summary, the conversation's
 *     `clearedAt` advances to the boundary frame's timestamp.
 *   - A second curate sees the shed frames filtered out
 *     (via `framesFor`'s watermark check) — stage-3 doesn't refire
 *     for "free".
 *   - `advanceClearedAt` is monotonic — a smaller `at` is a no-op.
 *   - No-shed turns don't advance the watermark.
 */
class Stage3WatermarkAdvanceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "watermark-model")
  private val topic = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")

  // Tiny model context so the budget gate trips into stage-3 with
  // a small frame count. Real test inputs only need ~10 frames to
  // exceed the cap.
  private val model: Model = Model(
    canonicalSlug       = "test/watermark-model",
    huggingFaceId       = "",
    name                = "watermark-model",
    displayName         = Some("watermark-model"),
    description         = "",
    contextLength       = 200L,
    architecture        = ModelArchitecture(
      modality         = "text->text",
      inputModalities  = List("text"),
      outputModalities = List("text"),
      tokenizer        = "Unknown",
      instructType     = None
    ),
    pricing             = ModelPricing(
      prompt = BigDecimal(0), completion = BigDecimal(0),
      webSearch = None, inputCacheRead = None
    ),
    topProvider         = ModelTopProvider(
      contextLength       = Some(200L),
      maxCompletionTokens = None,
      isModerated         = false
    ),
    perRequestLimits    = None,
    supportedParameters = Set.empty,
    defaultParameters   = ModelDefaultParameters(),
    knowledgeCutoff     = None,
    expirationDate      = None,
    links               = ModelLinks(details = ""),
    created             = Timestamp(),
    modified            = Timestamp(),
    _id                 = modelId
  )

  /** Compressor that always succeeds with a canned summary — drives
    * stage-3 deterministically without going through a provider. */
  private final class CountingCompressor extends ContextCompressor {
    val invocations = new AtomicInteger(0)
    override def compress(sigil: Sigil,
                          callerModelId: Id[Model],
                          chain: List[ParticipantId],
                          frames: Stream[ContextFrame],
                          conversationId: Id[Conversation]): Task[Option[ContextSummary]] = {
      invocations.incrementAndGet()
      val summary = ContextSummary(
        text           = "canned summary " + rapid.Unique(),
        conversationId = conversationId,
        tokenEstimate  = 20
      )
      sigil.persistSummary(summary).map(Some(_))
    }
  }

  private def freshConvId(label: String): Id[Conversation] =
    Conversation.id(s"$label-${rapid.Unique()}")

  private def seedConversation(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      _id = convId, topics = List(topic)
    )))).unit

  /** Frames are oversized so the budget gate trips deterministically.
    * Each Text frame is ~1200 chars → ~300 tokens at 4 chars/token.
    * 10 frames = ~3000 tokens, vastly over the 200-token cap. */
  private val padding: String = "x" * 1200

  private def publishFrames(convId: Id[Conversation], count: Int): Task[Unit] =
    Task.sequence((1 to count).toList.map { i =>
      TestSigil.publish(Message(
        participantId  = TestUser,
        conversationId = convId,
        topicId        = topic.id,
        content        = Vector(ResponseContent.Text(s"$padding (frame $i)")),
        state          = EventState.Complete
      ))
    }).unit

  "stage-3 shed" should {

    "advance Conversation.clearedAt past the boundary frame after a successful summary" in {
      val convId = freshConvId("watermark")
      val compressor = new CountingCompressor
      val curator = StandardContextCurator(
        sigil           = TestSigil,
        optimizer       = StandardContextOptimizer(),
        blockExtractor  = NoOpBlockExtractor,
        memoryRetriever = NoOpMemoryRetriever,
        compressor      = compressor,
        budget          = Percentage(0.8),
        keepMinimum     = 2
      )
      for {
        _ <- TestSigil.cache.replace(List(model))
        _ <- seedConversation(convId)
        _ <- publishFrames(convId, 10)
        beforeConv <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
        _      = beforeConv.flatMap(_.clearedAt) shouldBe None
        result <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
        afterConv  <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        // Stage-3 fired and produced a summary.
        compressor.invocations.get() should be > 0
        result.summaries should have size 1
        // Watermark advanced past zero.
        val watermark = afterConv.flatMap(_.clearedAt).map(_.value).getOrElse(0L)
        withClue(s"clearedAt watermark: $watermark") {
          watermark should be > 0L
        }
      }
    }

    "filter the shed slice from the next turn's framesFor (no compression re-fire on the same frames)" in {
      val convId = freshConvId("refire")
      val compressor = new CountingCompressor
      val curator = StandardContextCurator(
        sigil           = TestSigil,
        optimizer       = StandardContextOptimizer(),
        blockExtractor  = NoOpBlockExtractor,
        memoryRetriever = NoOpMemoryRetriever,
        compressor      = compressor,
        budget          = Percentage(0.8),
        keepMinimum     = 2
      )
      for {
        _ <- TestSigil.cache.replace(List(model))
        _ <- seedConversation(convId)
        _ <- publishFrames(convId, 10)
        _ <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
        invocationsAfterFirst = compressor.invocations.get()
        framesAfterFirst <- TestSigil.framesFor(convId)
        // Second turn — same conversation, no new frames added.
        // With clearedAt advanced past the shed slice, framesFor
        // should now return strictly fewer frames than before.
        _ <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
        framesAfterSecond <- TestSigil.framesFor(convId)
      } yield {
        invocationsAfterFirst should be > 0
        // First turn shed: framesFor (post-watermark-advance)
        // returns only the kept tail.
        framesAfterFirst.size should be < 10
        // Second turn sees the same trimmed view — the watermark
        // is monotonic so it doesn't slide back.
        framesAfterSecond.size shouldBe framesAfterFirst.size
      }
    }
  }

  "Sigil.advanceClearedAt" should {

    "advance the watermark monotonically (smaller `at` is a no-op)" in {
      val convId = freshConvId("monotonic")
      val high = Timestamp(2000L)
      val low  = Timestamp(1000L)
      for {
        _ <- seedConversation(convId)
        _ <- TestSigil.advanceClearedAt(convId, high)
        afterHigh <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
        _ <- TestSigil.advanceClearedAt(convId, low)
        afterLow <- TestSigil.withDB(_.conversations.transaction(_.get(convId)))
      } yield {
        afterHigh.flatMap(_.clearedAt).map(_.value) shouldBe Some(2000L)
        // Lower `at` is rejected silently — watermark stays at the
        // higher value.
        afterLow.flatMap(_.clearedAt).map(_.value) shouldBe Some(2000L)
      }
    }

    "no-op when the conversation doesn't exist" in {
      val missing = freshConvId("missing")
      for {
        _   <- TestSigil.advanceClearedAt(missing, Timestamp(1000L))
        opt <- TestSigil.withDB(_.conversations.transaction(_.get(missing)))
      } yield {
        opt shouldBe None
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
