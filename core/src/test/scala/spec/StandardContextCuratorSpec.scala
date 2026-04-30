package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import lightdb.time.Timestamp
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextSummary, Conversation, ConversationView}
import sigil.conversation.compression.{ContextCompressor, Fixed, NoOpContextCompressor, StandardContextCurator, StandardContextOptimizer}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.event.Event
import sigil.participant.ParticipantId

/**
 * Covers the curator's budget-driven split/summarize flow with a stub
 * compressor — no LLM, no DB roundtrip for the compression tool. The
 * ContextSummary returned is persisted via TestSigil so the full
 * roundtrip (including vector wiring being a no-op in TestSigil) is
 * exercised.
 */
class StandardContextCuratorSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val convId = Conversation.id(s"curator-${rapid.Unique()}")
  private val modelId: Id[Model] = Model.id("test", "model")

  // Seed the target Model — the curator always loads it before calling
  // the budget. Values are synthetic; only `contextLength` matters for
  // `Percentage`, and `Fixed` ignores the record entirely.
  TestSigil.cache.replace(List(Model(
    canonicalSlug = "test/model",
    huggingFaceId = "",
    name = "Test Model",
    description = "",
    contextLength = 1000L,
    architecture = ModelArchitecture(
      modality = "text->text",
      inputModalities = List("text"),
      outputModalities = List("text"),
      tokenizer = "None",
      instructType = None
    ),
    pricing = ModelPricing(prompt = BigDecimal(0), completion = BigDecimal(0), webSearch = None, inputCacheRead = None),
    topProvider = ModelTopProvider(contextLength = Some(1000L), maxCompletionTokens = None, isModerated = false),
    perRequestLimits = None,
    supportedParameters = Set.empty,
    knowledgeCutoff = None,
    expirationDate = None,
    links = ModelLinks(details = ""),
    created = Timestamp(),
    _id = modelId
  ))).sync()

  private def textFrame(s: String, id: String): ContextFrame.Text =
    ContextFrame.Text(s, TestUser, Id[Event](id))

  private def viewWith(frames: Vector[ContextFrame]): ConversationView =
    ConversationView(conversationId = convId, frames = frames, _id = ConversationView.idFor(convId))

  private class RecordingCompressor(result: Option[String]) extends ContextCompressor {
    @volatile var called: Int = 0
    @volatile var compressedFrames: Vector[ContextFrame] = Vector.empty
    override def compress(sigil: Sigil,
                          modelId: Id[Model],
                          chain: List[ParticipantId],
                          frames: Vector[ContextFrame],
                          conversationId: Id[Conversation]): Task[Option[ContextSummary]] = Task {
      called += 1
      compressedFrames = frames
      result.map(text => ContextSummary(text = text, conversationId = conversationId, tokenEstimate = 5))
    }
  }

  "StandardContextCurator" should {
    "be a no-op when frames fit under the budget" in {
      val compressor = new RecordingCompressor(None)
      val curator = StandardContextCurator(
        sigil = TestSigil,
        compressor = compressor,
        budget = Fixed(10_000),
        optimizer = new StandardContextOptimizer
      )
      val frames = (0 until 20).toVector.map(i => textFrame(s"line-$i", s"ev-$i"))
      curator.curate(viewWith(frames), modelId, chain = Nil).map { out =>
        out.conversationView.frames shouldBe frames
        out.summaries shouldBe empty
        compressor.called shouldBe 0
      }
    }

    "invoke the compressor and append a summary id when frames exceed the budget" in {
      val compressor = new RecordingCompressor(Some("compressed narrative"))
      val curator = StandardContextCurator(
        sigil = TestSigil,
        compressor = compressor,
        budget = Fixed(1),
        optimizer = new StandardContextOptimizer
      )
      // Make each frame have enough chars that TokenEstimator exceeds the tiny budget.
      val frames = (0 until 20).toVector.map(i =>
        textFrame(s"line $i — a fairly verbose sentence that eats up the token budget", s"ev-$i"))
      curator.curate(viewWith(frames), modelId, chain = Nil).map { out =>
        compressor.called shouldBe 1
        out.summaries should have size 1
        // The newer half is retained; the older half went to the compressor.
        out.conversationView.frames.size should be < frames.size
        compressor.compressedFrames.size should be > 0
      }
    }

    "fall through with the optimized view when the compressor returns None" in {
      val compressor = new RecordingCompressor(None)
      val curator = StandardContextCurator(
        sigil = TestSigil,
        compressor = compressor,
        budget = Fixed(1),
        optimizer = new StandardContextOptimizer
      )
      val frames = (0 until 20).toVector.map(i =>
        textFrame(s"second-pass line $i — verbose content exceeding the budget", s"ev2-$i"))
      curator.curate(viewWith(frames), modelId, chain = Nil).map { out =>
        compressor.called shouldBe 1
        out.summaries shouldBe empty
        out.conversationView.frames shouldBe frames
      }
    }

    "fall back to the optimized TurnInput without crashing when the model isn't in the cache (bug #40)" in {
      // A model id the cache has never seen — simulates a provider
      // that forgot to seed the registry on construction. The
      // curator must NOT throw; budget compression is skipped and
      // the optimized view flows through unchanged.
      val phantomId: Id[Model] = Model.id("phantom-provider", "phantom-model")
      val curator = StandardContextCurator(
        sigil = TestSigil,
        compressor = NoOpContextCompressor,
        budget = Fixed(10_000),
        optimizer = new StandardContextOptimizer
      )
      val frames = Vector(textFrame("user said hi", "u-1"), textFrame("agent replied", "a-1"))
      curator.curate(viewWith(frames), phantomId, chain = Nil).map { out =>
        // No NoSuchElementException — the body short-circuited to
        // the tentative TurnInput. Frames flow through unchanged
        // (no budget compression).
        out.conversationView.frames shouldBe frames
        out.summaries shouldBe empty
      }
    }

    "elide tool-call/result pairs whose Tool declares resultTtl = Some(0)" in {
      // The framework's `find_capability` and `change_mode` tools
      // both declare `resultTtl = Some(0)` — the curator pulls them
      // from `staticTools` and passes their names to the optimizer
      // as the elide-set. Verify both pairs are dropped from the
      // curated view.
      val curator = StandardContextCurator(
        sigil = TestSigil,
        compressor = NoOpContextCompressor,
        budget = Fixed(10_000),
        optimizer = new StandardContextOptimizer
      )
      val fcCallId = Id[Event]("fc-elide")
      val cmCallId = Id[Event]("cm-elide")
      val keep1 = Id[Event]("keep-1")
      val keep2 = Id[Event]("keep-2")
      val frames = Vector[ContextFrame](
        textFrame("user message", keep1.value),
        ContextFrame.ToolCall(sigil.tool.ToolName("find_capability"), "{\"keywords\":[\"x\"]}", fcCallId, TestUser, fcCallId),
        ContextFrame.ToolResult(fcCallId, "verbose schema dump", Id[Event]("fc-elide-r")),
        ContextFrame.ToolCall(sigil.tool.ToolName("change_mode"), "{\"mode\":\"X\"}", cmCallId, TestUser, cmCallId),
        ContextFrame.ToolResult(cmCallId, "Mode changed.", Id[Event]("cm-elide-r")),
        textFrame("agent reply", keep2.value)
      )
      curator.curate(viewWith(frames), modelId, chain = Nil).map { out =>
        // Only the two text frames survive; both ephemeral tool pairs are gone.
        out.conversationView.frames.collect { case t: ContextFrame.Text => t.content } shouldBe
          Vector("user message", "agent reply")
        out.conversationView.frames.collect { case _: ContextFrame.ToolCall => true }   shouldBe empty
        out.conversationView.frames.collect { case _: ContextFrame.ToolResult => true } shouldBe empty
      }
    }

    "run the BlockExtractor and merge extracted InformationSummary into TurnInput" in {
      import sigil.conversation.compression.StandardBlockExtractor
      import sigil.information.Information
      import fabric.rw.*
      case class BlockInfo(id: lightdb.id.Id[Information], content: String) extends Information derives RW
      val extractor = StandardBlockExtractor(
        toInformation = (c, id) => BlockInfo(id, c),
        minChars = 20
      )
      val curator = StandardContextCurator(
        sigil = TestSigil,
        optimizer = new StandardContextOptimizer,
        blockExtractor = extractor,
        compressor = NoOpContextCompressor,
        budget = Fixed(10_000)
      )
      val big = "A" * 50
      val frames = Vector(textFrame(big, "big"), textFrame("short", "short"))
      curator.curate(viewWith(frames), modelId, chain = Nil).map { out =>
        out.information should have size 1
        val extracted = out.conversationView.frames.head.asInstanceOf[ContextFrame.Text]
        extracted.content should include("Information[")
      }
    }
  }
}
