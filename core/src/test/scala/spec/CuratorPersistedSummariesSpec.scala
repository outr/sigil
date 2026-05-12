package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{Conversation, ContextFrame, ContextSummary, TopicEntry}
import sigil.conversation.compression.{NoOpBlockExtractor, NoOpContextCompressor, NoOpMemoryRetriever, Percentage, StandardContextCurator, StandardContextOptimizer}
import sigil.db.{Model, ModelArchitecture, ModelDefaultParameters, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.{Event, Message, MessageVisibility}
import sigil.signal.EventState
import sigil.tool.model.ResponseContent

/**
 * Regression for sigil bug #144 — the curator never loaded
 * persisted `ContextSummary` records, so any summary an app
 * generated (whether at import time or via a "session overview"
 * UX action) was invisible to subsequent turns. Verifies:
 *
 *   - `loadPersistedSummaries = true` (default) pulls
 *     `Sigil.summariesFor` into the turn's `TurnInput.summaries`
 *     so the budget gate accounts for the rendered cost AND the
 *     provider prompt-build path surfaces them.
 *   - `maxFramesPerTurn` caps the per-turn frame budget — older
 *     frames stay in the durable log but the curator skips them.
 *   - `loadPersistedSummaries = false` disables the lookup (opt-
 *     out for apps that never persist summaries).
 */
class CuratorPersistedSummariesSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "summaries-model")
  private val topic = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")

  private val model: Model = Model(
    canonicalSlug       = "test/summaries-model",
    huggingFaceId       = "",
    name                = "summaries-model",
    displayName         = Some("summaries-model"),
    description         = "",
    contextLength       = 100_000L,
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
      contextLength       = Some(100_000L),
      maxCompletionTokens = None,
      isModerated         = false
    ),
    perRequestLimits    = None,
    supportedParameters = Set.empty,
    defaultParameters   = ModelDefaultParameters(),
    knowledgeCutoff     = None,
    expirationDate      = None,
    links               = ModelLinks(details = ""),
    created             = lightdb.time.Timestamp(),
    modified            = lightdb.time.Timestamp(),
    _id                 = modelId
  )

  private def freshConvId(label: String): Id[Conversation] =
    Conversation.id(s"$label-${rapid.Unique()}")

  private def seedConversation(convId: Id[Conversation]): Task[Unit] =
    TestSigil.withDB(_.conversations.transaction(_.upsert(Conversation(
      _id = convId, topics = List(topic)
    )))).unit

  private def publishFrames(convId: Id[Conversation], count: Int): Task[Unit] =
    Task.sequence((1 to count).toList.map { i =>
      TestSigil.publish(Message(
        participantId  = TestUser,
        conversationId = convId,
        topicId        = topic.id,
        content        = Vector(ResponseContent.Text(s"message $i")),
        state          = EventState.Complete
      ))
    }).unit

  "StandardContextCurator.loadPersistedSummaries" should {

    "load persisted ContextSummary records into TurnInput.summaries when enabled" in {
      val convId = freshConvId("persisted")
      val curator = StandardContextCurator(
        sigil           = TestSigil,
        optimizer       = StandardContextOptimizer(),
        blockExtractor  = NoOpBlockExtractor,
        memoryRetriever = NoOpMemoryRetriever,
        compressor      = NoOpContextCompressor,
        budget          = Percentage(0.8)
      )
      for {
        _ <- TestSigil.cache.replace(List(model))
        _ <- seedConversation(convId)
        _ <- publishFrames(convId, 3)
        // Persist two app-authored summaries (compress-on-import
        // path). The curator must pull them on the next curate.
        s1 <- TestSigil.persistSummary(ContextSummary("epoch 1 summary text", convId, tokenEstimate = 10))
        s2 <- TestSigil.persistSummary(ContextSummary("epoch 2 summary text", convId, tokenEstimate = 10))
        result <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
      } yield {
        result.summaries.toSet shouldBe Set(s1._id, s2._id)
      }
    }

    "skip the summariesFor lookup when disabled" in {
      val convId = freshConvId("opt-out")
      val curator = StandardContextCurator(
        sigil                  = TestSigil,
        optimizer              = StandardContextOptimizer(),
        blockExtractor         = NoOpBlockExtractor,
        memoryRetriever        = NoOpMemoryRetriever,
        compressor             = NoOpContextCompressor,
        budget                 = Percentage(0.8),
        loadPersistedSummaries = false
      )
      for {
        _ <- TestSigil.cache.replace(List(model))
        _ <- seedConversation(convId)
        _ <- publishFrames(convId, 3)
        _ <- TestSigil.persistSummary(ContextSummary("should be ignored", convId, tokenEstimate = 10))
        result <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
      } yield {
        result.summaries shouldBe empty
      }
    }
  }

  "StandardContextCurator.maxFramesPerTurn" should {

    "cap the frame budget at the configured limit, dropping the oldest" in {
      val convId = freshConvId("framecap")
      val curator = StandardContextCurator(
        sigil            = TestSigil,
        optimizer        = StandardContextOptimizer(),
        blockExtractor   = NoOpBlockExtractor,
        memoryRetriever  = NoOpMemoryRetriever,
        compressor       = NoOpContextCompressor,
        budget           = Percentage(0.8),
        maxFramesPerTurn = 3
      )
      for {
        _ <- TestSigil.cache.replace(List(model))
        _ <- seedConversation(convId)
        _ <- publishFrames(convId, 10)
        result <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
      } yield {
        // Only the most-recent 3 frames survive the per-turn cap.
        result.frames should have size 3
        val texts = result.frames.collect { case t: ContextFrame.Text => t.content }
        texts shouldBe Vector("message 8", "message 9", "message 10")
      }
    }

    "pass through unchanged when conversation has fewer than maxFramesPerTurn frames" in {
      val convId = freshConvId("under-cap")
      val curator = StandardContextCurator(
        sigil            = TestSigil,
        optimizer        = StandardContextOptimizer(),
        blockExtractor   = NoOpBlockExtractor,
        memoryRetriever  = NoOpMemoryRetriever,
        compressor       = NoOpContextCompressor,
        budget           = Percentage(0.8),
        maxFramesPerTurn = 100
      )
      for {
        _ <- TestSigil.cache.replace(List(model))
        _ <- seedConversation(convId)
        _ <- publishFrames(convId, 4)
        result <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
      } yield {
        result.frames should have size 4
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
