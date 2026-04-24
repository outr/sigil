package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.db.{Model, ModelArchitecture, ModelLinks, ModelPricing, ModelTopProvider}
import sigil.provider.{CallId, Provider, ProviderCall, ProviderEvent, ProviderType, StopReason}
import sigil.tool.consult.RerankInput
import sigil.vector.{HybridSearch, LLMReranker, VectorSearchResult}
import spice.http.HttpRequest

/**
 * Coverage for [[LLMReranker]] against a stub provider that returns
 * a canned ordered id list. Verifies the reranker respects the
 * model's ordering, tolerates missing ids (appends unranked
 * candidates at the end), and short-circuits on ≤1 candidates.
 */
class LLMRerankerSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val modelId: Id[Model] = Model.id("test", "reranker-model")

  TestSigil.withDB(_.model.transaction(_.upsert(Model(
    canonicalSlug = "test/reranker-model",
    huggingFaceId = "",
    name = "Test Reranker Model",
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
  )))).sync()

  private class StubProvider(orderedIds: List[String]) extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override protected def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("StubProvider"))
    override protected def call(input: ProviderCall): Stream[ProviderEvent] = {
      val callId = CallId("rerank")
      Stream.emits(List(
        ProviderEvent.ToolCallStart(callId, "rerank_candidates"),
        ProviderEvent.ToolCallComplete(callId, RerankInput(orderedIds)),
        ProviderEvent.Done(StopReason.ToolCall)
      ))
    }
  }

  private def candidate(id: String, text: String, score: Double = 0.5): VectorSearchResult =
    VectorSearchResult(id = id, score = score, payload = Map(HybridSearch.TextKey -> text))

  private def rerankerFor(orderedIds: List[String]): LLMReranker = {
    TestSigil.reset()
    TestSigil.setProvider(Task.pure(new StubProvider(orderedIds)))
    LLMReranker(modelId = modelId, chain = List(TestUser, TestAgent))
  }

  "LLMReranker" should {
    "reorder candidates per the model's orderedIds" in {
      val reranker = rerankerFor(List("c3", "c1", "c2"))
      val candidates = List(
        candidate("c1", "first"),
        candidate("c2", "second"),
        candidate("c3", "third")
      )
      reranker.rerank(TestSigil, "which one is best?", candidates).map { out =>
        out.map(_.id) shouldBe List("c3", "c1", "c2")
      }
    }

    "append unranked candidates when the model omits some ids" in {
      val reranker = rerankerFor(List("c2"))
      val candidates = List(candidate("c1", "one"), candidate("c2", "two"), candidate("c3", "three"))
      reranker.rerank(TestSigil, "pick best", candidates).map { out =>
        out.head.id shouldBe "c2"
        out.map(_.id).toSet shouldBe Set("c1", "c2", "c3")
      }
    }

    "short-circuit when there is only one candidate (no LLM call needed)" in {
      val reranker = rerankerFor(List("never-called"))
      val candidates = List(candidate("only", "solo"))
      reranker.rerank(TestSigil, "q", candidates).map { out =>
        out shouldBe candidates
      }
    }

    "only feed the first maxCandidates to the LLM, preserving the tail unchanged" in {
      val reranker = rerankerFor(List("c2", "c1")).copy(maxCandidates = 2)
      val candidates = List(candidate("c1", "a"), candidate("c2", "b"), candidate("c3", "c"))
      reranker.rerank(TestSigil, "q", candidates).map { out =>
        // c1/c2 are reordered by the LLM (it returned [c2, c1]); c3
        // is beyond the pool so it keeps its original vector-rank
        // position at the end. The reranker must not drop candidates
        // — downstream slicing decides the final display size.
        out.map(_.id) shouldBe List("c2", "c1", "c3")
      }
    }
  }
}
