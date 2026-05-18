package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.conversation.compression.{Percentage, StandardContextCurator}
import sigil.conversation.compression.extract.MemoryExtractor
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, ContextSummary}
import sigil.db.Model
import sigil.event.Event
import sigil.participant.ParticipantId

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * Coverage for the curator's compression-time memory extractor.
 * When stage-3 frame compression fires, the extractor runs over
 * the about-to-be-shed slice on a background fiber so durable
 * facts hidden in older frames are captured before the lossy
 * summary collapses them.
 */
class StandardContextCuratorCompressionExtractorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Recording extractor — captures the (conversationId, frame
   * count) of every extractFromFrames call so the spec can assert
   * the curator reached the compression path with a non-empty
   * slice.
   */
  private class RecordingExtractor extends MemoryExtractor {
    val calls: AtomicReference[List[(Id[Conversation], Int)]] =
      new AtomicReference(Nil)
    override def extract(sigil: Sigil,
                         conversationId: Id[Conversation],
                         modelId: Id[Model],
                         chain: List[ParticipantId],
                         userMessage: String,
                         agentResponse: String): Task[List[ContextMemory]] =
      Task.pure(Nil)
    override def extractFromFrames(sigil: Sigil,
                                   conversationId: Id[Conversation],
                                   modelId: Id[Model],
                                   chain: List[ParticipantId],
                                   frames: Vector[ContextFrame]): Task[List[ContextMemory]] = {
      calls.updateAndGet(prior => (conversationId, frames.size) :: prior)
      Task.pure(Nil)
    }
  }

  /**
   * Synthetic compressor that always settles. Without this the
   * shed loop returns early on a None compress result.
   */
  private object SettlingCompressor extends sigil.conversation.compression.ContextCompressor {
    override def compress(sigil: Sigil,
                          modelId: Id[Model],
                          chain: List[ParticipantId],
                          frames: rapid.Stream[ContextFrame],
                          conversationId: Id[Conversation]): Task[Option[ContextSummary]] =
      frames.toList.flatMap { collected =>
        if (collected.isEmpty) Task.pure(None)
        else {
          val summary = ContextSummary(
            text = s"summary of ${collected.size} frames",
            conversationId = conversationId,
            tokenEstimate = 50
          )
          TestSigil.withDB(_.summaries.transaction(_.upsert(summary))).map(_ => Some(summary))
        }
      }
  }

  "StandardContextCurator compression-time memory extractor" should {

    "invoke extractFromFrames on the shed slice during stage-3 compression" in {
      val recorder = new RecordingExtractor
      val convId = Conversation.id(s"compression-extract-${rapid.Unique()}")

      // Seed enough frames to force budget pressure. The model has
      // a tight context (1000 tokens) so even modest text triggers
      // shedding once frames + memories exceed the budget.
      val seed = Conversation(topics = List(TestTopicEntry), _id = convId)
      val frames = (0 until 30).map { i =>
        sigil.event.Message(
          participantId = if (i % 2 == 0) TestUser else TestAgent,
          conversationId = convId,
          topicId = TestTopicId,
          content = Vector(sigil.tool.model.ResponseContent.Text(
            s"Turn $i: " + ("lorem ipsum dolor sit amet consectetur adipiscing elit " * 8)
          )),
          state = sigil.signal.EventState.Complete
        )
      }.toList

      val curator = StandardContextCurator(
        sigil = TestSigil,
        compressor = SettlingCompressor,
        compressionExtractor = recorder,
        budget = Percentage(0.5),
        keepMinimum = 2
      )

      // Use the priced model fixture from MessageModelIdAndCostSpec
      // (already registered with TestSigil.cache by other specs in
      // this suite). Falls back to fresh registration if unavailable.
      val modelId = sigil.db.Model.id("test", "compression-extract-model")
      val model = sigil.db.Model(
        canonicalSlug = "test/compression-extract-model",
        huggingFaceId = "",
        name = "compression-extract-model",
        description = "",
        contextLength = 4096L,
        architecture = sigil.db.ModelArchitecture(
          modality = "text->text",
          inputModalities = List("text"),
          outputModalities = List("text"),
          tokenizer = "None",
          instructType = None
        ),
        pricing = sigil.db.ModelPricing(
          prompt = BigDecimal(0),
          completion = BigDecimal(0),
          webSearch = None,
          inputCacheRead = None
        ),
        topProvider = sigil.db.ModelTopProvider(contextLength = Some(4096L), maxCompletionTokens = None, isModerated = false),
        perRequestLimits = None,
        supportedParameters = Set.empty,
        knowledgeCutoff = None,
        expirationDate = None,
        links = sigil.db.ModelLinks(details = ""),
        created = lightdb.time.Timestamp(),
        _id = modelId
      )
      TestSigil.cache.merge(List(model)).sync()

      val task = for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(seed))).unit
        _ <- Task.sequence(frames.map(TestSigil.publish)).unit
        _ <- curator.curate(convId, modelId, chain = List(TestUser, TestAgent))
        _ <- Task.sleep(200.millis) // give the background fiber a tick
      } yield ()
      task.map { _ =>
        val recorded = recorder.calls.get()
        recorded should not be empty
        recorded.foreach { case (cid, n) =>
          cid shouldBe convId
          n should be > 0
        }
        succeed
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
