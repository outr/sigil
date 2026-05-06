package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary, Conversation, ConversationView}
import sigil.conversation.compression.{ContextCompressor, Fixed, MemoryRetrievalResult, MemoryRetriever, StandardContextCurator, TokenEstimator}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.tokenize.HeuristicTokenizer

import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for sigil bug #23 — Stage 3 frame compression must iterate
 * (or shed proportionally) when a single split-and-summarise leaves
 * the kept frames still over budget. The previous implementation
 * halved exactly once, so a freshly-imported 9k-event conversation
 * stayed ~40× over budget after compression.
 */
class CuratorIterativeShedSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Id[Model]("test/curator-iterative-shed")

  private val tinyModel: Model = Model(
    canonicalSlug = "test/curator-iterative-shed",
    huggingFaceId = "",
    name = "curator-iterative-shed",
    description = "Synthetic curator iterative-shed tests",
    contextLength = 4000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(4000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSigil.cache.merge(List(tinyModel)).sync()
  }

  private class FixedMemoryRetriever(critical: Vector[Id[ContextMemory]],
                                     retrieved: Vector[Id[ContextMemory]]) extends MemoryRetriever {
    override def retrieve(sigil: Sigil,
                          view: ConversationView,
                          chain: List[ParticipantId]): Task[MemoryRetrievalResult] =
      Task.pure(MemoryRetrievalResult(memories = retrieved, criticalMemories = critical))
  }

  // Compressor that records every invocation (frames-count + summary-text-length) so
  // tests can verify iterative behaviour. Returns a small fixed-size summary so the
  // post-compression turn fits below the cap once enough frames are dropped.
  private class CountingCompressor(summaryTokenCost: Int = 10) extends ContextCompressor {
    val invocations: AtomicInteger = new AtomicInteger(0)
    @volatile var lastFramesCount: Int = -1

    override def compress(sigilArg: Sigil,
                          modelId: Id[Model],
                          chain: List[ParticipantId],
                          frames: Vector[ContextFrame],
                          conversationId: Id[Conversation]): Task[Option[ContextSummary]] = {
      invocations.incrementAndGet()
      lastFramesCount = frames.size
      val summary = ContextSummary(
        text = "x" * (summaryTokenCost * 4),
        conversationId = conversationId,
        tokenEstimate = summaryTokenCost
      )
      sigilArg.persistSummary(summary).map(Some(_))
    }
  }

  private def makeFrames(n: Int): Vector[ContextFrame] = (0 until n).map { i =>
    ContextFrame.Text(s"frame-$i: " + ("text " * 30), TestUser, Id[Event]()): ContextFrame
  }.toVector

  "StandardContextCurator Stage 3" should {

    "iterate when one split-and-summarise leaves the kept half still over budget" in {
      // 100 frames at ~30 tokens each = ~3000 tokens. Budget = 80.
      // A single halving leaves 50 frames (~1500 tokens) — still 18× over.
      // The iterative path must keep halving (or jump aggressively) until
      // the kept frames fit OR `kept.size <= keepMinimum`.
      val convId = Conversation.id(s"iterate-${rapid.Unique()}")
      val retriever = new FixedMemoryRetriever(Vector.empty, Vector.empty)
      val compressor = new CountingCompressor()
      val curator = StandardContextCurator(
        sigil = TestSigil,
        memoryRetriever = retriever,
        compressor = compressor,
        budget = Fixed(80),
        keepMinimum = 2,
        tokenizer = HeuristicTokenizer
      )
      val frames = makeFrames(100)
      val view = ConversationView(
        conversationId = convId,
        frames = frames,
        _id = ConversationView.idFor(convId)
      )

      curator.curate(view, modelId, List(TestUser, TestAgent)).map { turnInput =>
        compressor.invocations.get should be >= 1
        // Kept-frame side must end at or below keepMinimum (the floor) —
        // i.e. the iterative shed actually drove the kept set all the
        // way down rather than stopping after a single halving.
        turnInput.conversationView.frames.size should (be <= 2)
        turnInput.summaries should not be empty
      }
    }

    "drive iterative shedding to fit the budget when content allows OR collapse to keepMinimum" in {
      // 50 frames; budget loose enough that the iterative path can land
      // under cap without bottoming out — assert it actually fits rather
      // than just hitting the floor. Frames at ~30 tok each + 8-tok
      // summary, budget 600 → shed should leave ~15-20 frames + summary.
      val convId = Conversation.id(s"fits-${rapid.Unique()}")
      val retriever = new FixedMemoryRetriever(Vector.empty, Vector.empty)
      val compressor = new CountingCompressor(summaryTokenCost = 8)
      val curator = StandardContextCurator(
        sigil = TestSigil,
        memoryRetriever = retriever,
        compressor = compressor,
        budget = Fixed(600),
        keepMinimum = 2,
        tokenizer = HeuristicTokenizer
      )
      val view = ConversationView(
        conversationId = convId,
        frames = makeFrames(50),
        _id = ConversationView.idFor(convId)
      )

      curator.curate(view, modelId, List(TestUser, TestAgent)).flatMap { turnInput =>
        val summaryIds = turnInput.summaries
        Task.sequence(summaryIds.toList.map(id => TestSigil.withDB(_.summaries.transaction(_.get(id)))))
          .map(_.flatten.toVector)
          .map { resolvedSummaries =>
            val tokens = TokenEstimator.estimateCuratorSections(
              frames = turnInput.conversationView.frames,
              criticalMemories = Vector.empty,
              memories = Vector.empty,
              summaries = resolvedSummaries,
              information = turnInput.information,
              tokenizer = HeuristicTokenizer
            )
            tokens should be <= 600
            turnInput.summaries should have size 1
            turnInput.conversationView.frames.size should be < 50
          }
      }
    }

    "aggressively collapse when way over budget (single iteration drives kept to keepMinimum)" in {
      // 5000 frames at ~30 tokens each = ~150_000 tokens. Budget = 100.
      // The aggressive path (`current > cap * 3`) should jump straight to
      // keepMinimum without 12 halving rounds. A handful of compressor
      // invocations is fine; tens or hundreds means we're not collapsing
      // aggressively when we should.
      val convId = Conversation.id(s"aggressive-${rapid.Unique()}")
      val retriever = new FixedMemoryRetriever(Vector.empty, Vector.empty)
      val compressor = new CountingCompressor(summaryTokenCost = 12)
      val curator = StandardContextCurator(
        sigil = TestSigil,
        memoryRetriever = retriever,
        compressor = compressor,
        budget = Fixed(100),
        keepMinimum = 2,
        tokenizer = HeuristicTokenizer
      )
      val view = ConversationView(
        conversationId = convId,
        frames = makeFrames(5000),
        _id = ConversationView.idFor(convId)
      )

      curator.curate(view, modelId, List(TestUser, TestAgent)).map { turnInput =>
        compressor.invocations.get should be <= 5
        turnInput.conversationView.frames.size shouldBe 2
        turnInput.summaries should have size 1
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
