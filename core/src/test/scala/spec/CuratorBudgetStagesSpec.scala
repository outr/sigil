package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.{GlobalSpace, Sigil}
import sigil.conversation.{ContextFrame, ContextMemory, ContextSummary, Conversation, ConversationView, MemorySource}
import sigil.conversation.compression.{
  ContextCompressor, Fixed, MemoryRetrievalResult, MemoryRetriever, NoOpBlockExtractor,
  StandardContextCurator
}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.tokenize.HeuristicTokenizer

/**
 * Coverage for the multi-stage shedding pipeline in
 * [[StandardContextCurator.budgetResolve]]:
 *
 *   - Stage 1: drop non-critical retrieved memories first.
 *   - Stage 3: compress older frames if Stage 1 alone didn't fit.
 *
 * Stage 2 (drop unreferenced Information) requires a richer fixture
 * with a `BlockExtractor` populating `TurnInput.information` —
 * exercised separately in the AbstractContextCuratorSpec; this spec
 * focuses on the memory + frame stages that drive most real
 * shed events per Phase 0 measurements.
 *
 * Critical memories are NEVER touched by the curator — verified by
 * persisting a Critical memory and asserting it survives every stage.
 */
class CuratorBudgetStagesSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Id[Model]("test/curator-budget")

  private val tinyModel: Model = Model(
    canonicalSlug = "test/curator-budget",
    huggingFaceId = "",
    name = "curator-budget",
    description = "Synthetic curator budget tests",
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

  // Memory retriever that returns a controlled set of (criticalIds, retrievedIds).
  private class FixedMemoryRetriever(critical: Vector[Id[ContextMemory]],
                                     retrieved: Vector[Id[ContextMemory]]) extends MemoryRetriever {
    override def retrieve(sigil: Sigil,
                          view: ConversationView,
                          chain: List[ParticipantId]): Task[MemoryRetrievalResult] =
      Task.pure(MemoryRetrievalResult(memories = retrieved, criticalMemories = critical))
  }

  // Compressor that records its inputs and returns a fixed summary.
  private class RecordingCompressor extends ContextCompressor {
    @volatile var lastFramesCount: Int = -1

    override def compress(sigilArg: Sigil,
                          modelId: Id[Model],
                          chain: List[ParticipantId],
                          frames: Vector[ContextFrame],
                          conversationId: Id[Conversation]): Task[Option[ContextSummary]] = {
      lastFramesCount = frames.size
      val summary = ContextSummary(
        text = s"compressed ${frames.size} frames",
        conversationId = conversationId,
        tokenEstimate = 30
      )
      sigilArg.persistSummary(summary).map(Some(_))
    }
  }

  private def makeFrames(n: Int): Vector[ContextFrame] = (0 until n).map { i =>
    ContextFrame.Text(s"frame-$i: " + ("text " * 30), TestUser, Id[Event]()): ContextFrame
  }.toVector

  "StandardContextCurator.budgetResolve" should {

    "drop non-critical retrieved memories before compressing frames (Stage 1)" in {
      // Persist 5 retrieved memories (~80 tokens each) + 1 critical
      // (~10 tokens). With a tight budget, Stage 1 should clear the
      // retrieved set; frame compression shouldn't fire.
      val convId = Conversation.id(s"stage1-${rapid.Unique()}")
      val critical = ContextMemory(
        fact = "be concise",
        label = "Be concise",
        summary = "Always be concise.",
        source = MemorySource.Explicit, pinned = true,
        spaceId = GlobalSpace,
        key = Some(s"crit-${rapid.Unique()}")
      )
      val retrieved = (1 to 5).map { i =>
        ContextMemory(
          fact = "x" * 320,  // ~80 tokens
          label = s"retrieved-$i",
          summary = "x" * 320,
          source = MemorySource.Compression,
          spaceId = GlobalSpace,
          key = Some(s"ret-$i-${rapid.Unique()}")
        )
      }.toVector

      for {
        _ <- TestSigil.persistMemory(critical)
        _ <- Task.sequence(retrieved.toList.map(TestSigil.persistMemory))
        retriever = new FixedMemoryRetriever(Vector(critical._id), retrieved.map(_._id))
        compressor = new RecordingCompressor
        // Budget 200 tokens — way under the retrieved+critical sum, forces Stage 1.
        curator = StandardContextCurator(
          sigil = TestSigil,
          memoryRetriever = retriever,
          compressor = compressor,
          budget = Fixed(200)
        )
        view = ConversationView(
          conversationId = convId,
          frames = makeFrames(2),
          _id = ConversationView.idFor(convId)
        )
        turnInput <- curator.curate(view, modelId, List(TestUser, TestAgent))
      } yield {
        // Stage 1 cleared the retrieved memories.
        turnInput.memories shouldBe empty
        // Critical memory still in TurnInput — never shed.
        turnInput.criticalMemories should contain (critical._id)
        // Frame compression didn't fire (Stage 1 alone fit).
        compressor.lastFramesCount shouldBe -1
      }
    }

    "fall through to frame compression when memory drops aren't enough (Stage 3)" in {
      // No retrieved memories; just lots of frames over budget. Stage 3
      // (compressor) should fire since stages 1+2 have nothing to drop.
      val convId = Conversation.id(s"stage3-${rapid.Unique()}")
      val retriever = new FixedMemoryRetriever(Vector.empty, Vector.empty)
      val compressor = new RecordingCompressor
      val curator = StandardContextCurator(
        sigil = TestSigil,
        memoryRetriever = retriever,
        compressor = compressor,
        budget = Fixed(50),
        keepMinimum = 2
      )
      val view = ConversationView(
        conversationId = convId,
        frames = makeFrames(20),  // way over 50-token budget
        _id = ConversationView.idFor(convId)
      )
      curator.curate(view, modelId, List(TestUser, TestAgent)).map { turnInput =>
        compressor.lastFramesCount should be > 0
        turnInput.summaries should not be empty
        // newer half kept on the view; older half compressed
        turnInput.conversationView.frames.size should be < 20
      }
    }

    "leave critical memories untouched even when budget is impossibly tight" in {
      // Critical-only memories. Budget so tight that ANY content overflows.
      // Stage 1 has nothing to shed (no non-critical); Stage 3 hits frames
      // (none here). Critical memory MUST survive — the framework's
      // "criticals are inviolable" invariant.
      val convId = Conversation.id(s"crit-survives-${rapid.Unique()}")
      val critical = ContextMemory(
        fact = "must always be present",
        label = "Must always be present",
        summary = "Pinned must-survive directive.",
        source = MemorySource.Explicit, pinned = true,
        spaceId = GlobalSpace,
        key = Some(s"crit-survives-${rapid.Unique()}")
      )

      for {
        _ <- TestSigil.persistMemory(critical)
        retriever = new FixedMemoryRetriever(Vector(critical._id), Vector.empty)
        compressor = new RecordingCompressor
        curator = StandardContextCurator(
          sigil = TestSigil,
          memoryRetriever = retriever,
          compressor = compressor,
          budget = Fixed(10) // smaller than the critical memory itself
        )
        view = ConversationView(
          conversationId = convId,
          frames = Vector.empty,
          _id = ConversationView.idFor(convId)
        )
        turnInput <- curator.curate(view, modelId, List(TestUser, TestAgent))
      } yield {
        // Critical memory still in TurnInput — never shed regardless of budget.
        turnInput.criticalMemories should contain (critical._id)
      }
    }

    "succeed when the conversation has no frames (empty conversation edge case)" in {
      // Curator should produce a sensible TurnInput even when the
      // conversation has zero frames (first-turn scenario where the
      // user hasn't sent anything yet — agent greeting flow). No
      // shedding fires; nothing to drop.
      val convId = Conversation.id(s"empty-conv-${rapid.Unique()}")
      val retriever = new FixedMemoryRetriever(Vector.empty, Vector.empty)
      val compressor = new RecordingCompressor
      val curator = StandardContextCurator(
        sigil = TestSigil,
        memoryRetriever = retriever,
        compressor = compressor,
        budget = Fixed(2000)
      )
      val view = ConversationView(
        conversationId = convId,
        frames = Vector.empty,
        _id = ConversationView.idFor(convId)
      )
      curator.curate(view, modelId, List(TestUser, TestAgent)).map { turnInput =>
        turnInput.conversationView.frames shouldBe empty
        turnInput.memories shouldBe empty
        turnInput.criticalMemories shouldBe empty
        compressor.lastFramesCount shouldBe -1  // compressor never invoked
      }
    }

    "fit-as-is when budget is comfortably above estimated cost (no shedding)" in {
      // Generous budget; small content. No shedding stage should fire.
      val convId = Conversation.id(s"no-shed-${rapid.Unique()}")
      val retriever = new FixedMemoryRetriever(Vector.empty, Vector.empty)
      val compressor = new RecordingCompressor
      val curator = StandardContextCurator(
        sigil = TestSigil,
        memoryRetriever = retriever,
        compressor = compressor,
        budget = Fixed(100_000)  // huge budget — nothing to shed
      )
      val view = ConversationView(
        conversationId = convId,
        frames = makeFrames(3),
        _id = ConversationView.idFor(convId)
      )
      curator.curate(view, modelId, List(TestUser, TestAgent)).map { turnInput =>
        turnInput.conversationView.frames.size shouldBe 3
        compressor.lastFramesCount shouldBe -1  // compressor never invoked
        turnInput.summaries shouldBe empty       // no compression summary
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
