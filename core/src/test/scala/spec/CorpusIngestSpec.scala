package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.SpaceId
import sigil.conversation.{ContextKey, MemorySource}
import sigil.db.Model
import sigil.provider.{CallId, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.tool.consult.{ExtractMemoriesInput, ExtractMemoriesTool, ExtractedMemory}
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger

/**
 * `Sigil.ingestCorpusMemories` splits a dense passage into atomic,
 * self-contained memories: one extraction consult per passage, one
 * record per fact, `source = Corpus`, provenance under
 * `ContextKey.CorpusPassage`, keyed facts versioned, and a failed
 * passage skipped rather than failing the ingest.
 */
class CorpusIngestSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "corpus-ingest")
  TestSigil.testModel(modelId)

  private val densePassage =
    "He keeps his cigars in the coal-scuttle, his tobacco in the toe end of a Persian slipper, " +
      "and his unanswered correspondence transfixed by a jack-knife into the centre of the mantelpiece."

  /**
   * Splits the dense passage into three atomic facts; answers any
   * other passage with nothing; fails on a passage marked `BOOM`.
   */
  final private class SplittingProvider extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] = {
      calls.incrementAndGet()
      val prompt = input.messages.map(_.toString).mkString
      if (prompt.contains("BOOM")) Stream.force(Task.error(new RuntimeException("consult exploded")))
      else if (prompt.contains("coal-scuttle")) {
        val callId = CallId(s"split-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, "extract_memories"),
          ProviderEvent.toolCall(callId, ExtractMemoriesTool)(ExtractMemoriesInput(List(
            ExtractedMemory("Sherlock Holmes keeps his cigars in the coal-scuttle.", "Cigars"),
            ExtractedMemory(
              "Sherlock Holmes keeps his tobacco in the toe end of a Persian slipper.",
              "Tobacco",
              key = Some("holmes.tobacco_location")),
            ExtractedMemory("Sherlock Holmes pins unanswered correspondence to the mantelpiece with a jack-knife.", "Correspondence")
          ))),
          ProviderEvent.Done(StopReason.Complete)
        ))
      } else Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
    }
  }

  private val provider = new SplittingProvider

  override protected def afterAll(): Unit = {
    TestSigil.reset()
    super.afterAll()
  }

  "ingestCorpusMemories" should {
    "split a dense passage into one atomic memory per fact with provenance" in {
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set[SpaceId](TestSpace)))
      TestSigil.ingestCorpusMemories(
        passages = List("musgrave.txt#3" -> densePassage),
        space = TestSpace,
        modelId = modelId,
        chain = List(TestUser, TestAgent)
      ).map { stored =>
        stored should have size 3
        stored.map(_.fact) should contain("Sherlock Holmes keeps his tobacco in the toe end of a Persian slipper.")
        stored.foreach { m =>
          m.source shouldBe MemorySource.Corpus
          m.spaceId shouldBe TestSpace
          m.summary shouldBe m.fact
          m.extraContext.get(ContextKey.CorpusPassage) shouldBe Some("musgrave.txt#3")
          m.createdBy shouldBe Some(TestAgent)
        }
        stored.find(_.key.contains("holmes.tobacco_location")) should not be empty
      }
    }

    "refresh rather than duplicate a keyed fact restated by a second passage" in {
      for {
        again <- TestSigil.ingestCorpusMemories(
          passages = List("musgrave.txt#4" -> densePassage),
          space = TestSpace,
          modelId = modelId,
          chain = List(TestUser, TestAgent))
        keyed <- TestSigil.withDB(_.memories.transaction(
          _.query.filter(_.key === Some("holmes.tobacco_location")).toList))
      } yield {
        again should have size 3
        // One current version of the keyed slot; the keyless facts insert twice.
        keyed.count(_.validUntil.isEmpty) shouldBe 1
      }
    }

    "skip a passage whose consult fails and still persist the others" in {
      val before = provider.calls.get()
      TestSigil.ingestCorpusMemories(
        passages = List("bad.txt#1" -> "BOOM this passage detonates", "musgrave.txt#5" -> densePassage),
        space = TestSpace,
        modelId = modelId,
        chain = List(TestUser, TestAgent)
      ).map { stored =>
        provider.calls.get() shouldBe before + 2
        stored should have size 3
      }
    }

    "contribute nothing for an empty or fact-free passage" in
      TestSigil.ingestCorpusMemories(
        passages = List("blank.txt#1" -> "   ", "filler.txt#1" -> "Nothing of substance here."),
        space = TestSpace,
        modelId = modelId,
        chain = List(TestUser, TestAgent)
      ).map(_ shouldBe empty)
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
