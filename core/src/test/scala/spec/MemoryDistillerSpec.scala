package spec

import lightdb.id.Id
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.conversation.{ContextMemory, MemorySource, UpsertMemoryResult}
import sigil.conversation.compression.ConsultMemoryDistiller
import sigil.db.Model
import sigil.embedding.{EmbeddingProvider, EmbeddingRef}
import sigil.provider.{CallId, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.tool.consult.{DistillMemoryInput, DistillMemoryTool}
import sigil.vector.InMemoryVectorIndex
import spice.http.HttpRequest

import java.util.concurrent.atomic.AtomicInteger

/**
 * Ingest-time distillation (`Sigil.memoryDistiller`): a long fact
 * persists with a genuine one-line `summary` (the per-turn render
 * form, handle included) and retrieval-optimized `embeddedText` (what
 * the vector point and provenance stamp are built from). Short facts
 * and caller-authored summaries skip the consult, and a keyed refresh
 * whose incoming summary is the extractors' `summary = fact` copy
 * does not clobber the distilled record.
 */
class MemoryDistillerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers with BeforeAndAfterAll {
  TestSigil.initFor(getClass.getSimpleName)

  private val distillModelId: Id[Model] = Model.id("test", "distiller")
  TestSigil.testModel(distillModelId)

  private val distilledSummary = "Keeps tobacco in a Persian slipper."
  private val retrievalText =
    "Sherlock Holmes stores his pipe tobacco inside the toe end of a Persian slipper on the mantelpiece at 221B Baker Street."

  private final class DistillScriptProvider extends Provider {
    val calls = new AtomicInteger(0)
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      if (input.tools.exists(_.name.value == "distill_memory")) {
        calls.incrementAndGet()
        val callId = CallId(s"distill-${rapid.Unique()}")
        Stream.emits(List(
          ProviderEvent.ToolCallStart(callId, "distill_memory"),
          ProviderEvent.toolCall(callId, DistillMemoryTool)(DistillMemoryInput(
            summary = distilledSummary,
            retrievalText = Some(retrievalText)
          )),
          ProviderEvent.Done(StopReason.Complete)
        ))
      } else Stream.emits(List(ProviderEvent.Done(StopReason.Complete)))
  }

  private object StubEmbedding extends EmbeddingProvider {
    override val id: String = "distill-stub"
    override val dimensions: Int = 2
    override def embed(text: String): Task[Vector[Double]] = Task.pure(Vector(1.0, 0.0))
    override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
      Task.pure(texts.map(_ => Vector(1.0, 0.0)))
  }

  private val provider = new DistillScriptProvider
  private val longFact =
    "He keeps his tobacco in the toe end of a Persian slipper upon the mantelpiece, his cigars " +
      "in the coal-scuttle, and his unanswered correspondence transfixed by a jack-knife into the " +
      "very centre of his wooden mantelpiece, as any visitor to his rooms quickly discovers."

  private def corpusMemory(fact: String, key: Option[String] = None): ContextMemory =
    ContextMemory(
      fact = fact,
      label = "corpus passage",
      summary = fact,
      source = MemorySource.Explicit,
      spaceId = TestSpace,
      key = key
    )

  override protected def afterAll(): Unit = {
    TestSigil.reset()
    super.afterAll()
  }

  "the memory distiller" should {
    "wire the fixtures" in {
      TestSigil.setProvider(Task.pure(provider))
      TestSigil.setEmbeddingProvider(StubEmbedding)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)
      TestSigil.setMemoryDistiller(ConsultMemoryDistiller(distillModelId, minFactChars = 100))
      succeed
    }

    "distill a long fact into a summary plus retrieval text and stamp the embedding from it" in {
      TestSigil.persistMemory(corpusMemory(longFact)).map { stored =>
        stored.summary shouldBe distilledSummary
        stored.embeddedText shouldBe Some(retrievalText)
        stored.embeddingSource shouldBe retrievalText
        // The vector point and provenance stamp are built from the
        // retrieval text, not the raw fact.
        stored.embedding.map(_.contentHash) shouldBe Some(EmbeddingRef.hash(retrievalText))
        // The rendered line elides and carries the drill-down handle.
        sigil.provider.ContextSections.memoryRenderText(stored) shouldBe
          s"""$distilledSummary [full: lookup("${stored._id.value}")]"""
      }
    }

    "skip short facts without spending a consult" in {
      val before = provider.calls.get()
      TestSigil.persistMemory(corpusMemory("Prefers dark roast coffee.")).map { stored =>
        provider.calls.get() shouldBe before
        stored.summary shouldBe "Prefers dark roast coffee."
        stored.embeddedText shouldBe None
      }
    }

    "leave sources outside the distiller's policy untouched" in {
      val before = provider.calls.get()
      // Per-turn extraction output is concise by construction; the
      // default policy distills only Explicit and Corpus records.
      val extracted = corpusMemory(longFact).copy(source = MemorySource.Compression)
      TestSigil.persistMemory(extracted).map { stored =>
        provider.calls.get() shouldBe before
        stored.summary shouldBe longFact
        stored.embeddedText shouldBe None
      }
    }

    "respect a caller-authored summary" in {
      val before = provider.calls.get()
      val authored = corpusMemory(longFact).copy(summary = "Author wrote this line herself.")
      TestSigil.persistMemory(authored).map { stored =>
        provider.calls.get() shouldBe before
        stored.summary shouldBe "Author wrote this line herself."
        stored.embeddedText shouldBe None
      }
    }

    "keep the distilled summary and embedded text across a keyed refresh" in {
      val key = Some("holmes.tobacco")
      for {
        first <- TestSigil.upsertMemoryByKey(corpusMemory(longFact, key))
        _ = TestSigil.resetMemoryDistiller()
        second <- TestSigil.upsertMemoryByKey(corpusMemory(longFact, key))
      } yield {
        first shouldBe a[UpsertMemoryResult.Stored]
        second shouldBe a[UpsertMemoryResult.Refreshed]
        second.memory.summary shouldBe distilledSummary
        second.memory.embeddedText shouldBe Some(retrievalText)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
