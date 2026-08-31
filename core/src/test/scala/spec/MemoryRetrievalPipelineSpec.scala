package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.conversation.{ContextFrame, ContextMemory, Conversation, ConversationView, MemorySource}
import sigil.conversation.compression.StandardMemoryRetriever
import sigil.conversation.compression.retrieval.{
  BudgetStage, FuseStage, MemoryReranker, MemoryRetrievalContext, MemoryRetrievalState,
  RecallStage, RerankStage
}
import sigil.event.Event
import sigil.vector.InMemoryVectorIndex

import scala.concurrent.duration.*

/**
 * Coverage for the declared retrieval pipeline's new stages:
 *
 *   - Fuse — recency breaks exact RRF ties toward the fresher record,
 *     reinforcement gives a bounded (never dominating) boost, and
 *     weights-zero reproduces the legacy confidence-weighted RRF
 *     ordering exactly.
 *   - Rerank — a stubbed [[MemoryReranker]] reorders; `None` is an
 *     identity pass.
 *   - Budget — the optional token cap keeps a best-first prefix.
 *   - Record — an end-to-end retrieve bumps `accessCount` /
 *     `lastAccessedAt` on the surfaced memories.
 */
class MemoryRetrievalPipelineSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val now = Timestamp()

  private def ctx(sigil: Sigil = TestSigil): MemoryRetrievalContext = MemoryRetrievalContext(
    sigil = sigil,
    conversationId = Conversation.id(s"pipe-${rapid.Unique()}"),
    query = "test query",
    spaces = Set(MemoryTestSpace),
    currentMode = None,
    now = now,
    limit = 5,
    candidatePool = 10
  )

  private def mem(fact: String,
                  created: Timestamp = now,
                  modified: Timestamp = now,
                  accessCount: Int = 0,
                  confidence: Double = 1.0): ContextMemory = ContextMemory(
    fact = fact,
    label = fact.take(24),
    summary = fact,
    source = MemorySource.Explicit,
    spaceId = MemoryTestSpace,
    confidence = confidence,
    accessCount = accessCount,
    created = created,
    modified = modified
  )

  "FuseStage" should {
    "break an exact RRF tie toward the fresher memory" in {
      val stale = mem(
        "stale fact",
        created = Timestamp(now.value - 30 * 24 * 60 * 60 * 1000L),
        modified = Timestamp(now.value - 30 * 24 * 60 * 60 * 1000L))
      val fresh = mem("fresh fact")
      // Symmetric leg weights, each memory rank 1 in exactly one leg —
      // identical base scores. The stale record accumulates first, so
      // weights-zero (stable sort) keeps it first; default weights let
      // recency break the tie toward the fresher record.
      val state = MemoryRetrievalState(lexical = Vector(stale), vectorHits = Vector(fresh))
      val tied = FuseStage(lexicalWeight = 1.0, vectorWeight = 1.0, recencyWeight = 0.0, reinforcementWeight = 0.0)
      val boosted = FuseStage(lexicalWeight = 1.0, vectorWeight = 1.0)
      for {
        zero <- tied.run(state, ctx())
        defaulted <- boosted.run(state, ctx())
      } yield {
        zero.ranked.map(_.fact) should be(Vector("stale fact", "fresh fact"))
        defaulted.ranked.map(_.fact) should be(Vector("fresh fact", "stale fact"))
      }
    }

    "give a frequently-accessed memory a bounded boost that cannot dominate" in {
      val cold = mem("cold rank one")
      val hot = mem("hot rank four", accessCount = 1_000_000)
      val filler1 = mem("filler two")
      val filler2 = mem("filler three")
      // Same leg, ranks 1 / 2 / 3 / 4. The hot record's saturated
      // reinforcement term (< reinforcementWeight) cannot close a
      // three-rank base-score gap, so rank 1 stays first.
      val state = MemoryRetrievalState(lexical = Vector(cold, filler1, filler2, hot))
      FuseStage().run(state, ctx()).map { fused =>
        fused.ranked.head.fact should be("cold rank one")
        // But the bounded boost DOES lift the hot record over its
        // immediate (equally stale, unaccessed) predecessor.
        fused.ranked.indexWhere(_.fact == "hot rank four") should be < 3
      }
    }

    "reproduce the confidence-weighted RRF formula exactly at weights zero" in {
      val a = mem("alpha", confidence = 0.6)
      val b = mem("bravo")
      val c = mem("charlie", confidence = 0.9)
      val d = mem("delta")
      val lexical = Vector(a, b, c)
      val vector = Vector(c, d, a)
      val expected = referenceRrf(List((lexical, 2.0), (vector, 1.0)), k = 60)
      val stage = FuseStage(recencyWeight = 0.0, reinforcementWeight = 0.0)
      stage.run(MemoryRetrievalState(lexical = lexical, vectorHits = vector), ctx()).map { fused =>
        fused.ranked.map(_._id).toList should be(expected)
      }
    }

    // Ported from the standalone RRF unit coverage the retriever's
    // `rrfFuse` helper carried before the pipeline subsumed it — the
    // fusion math is what silently degrades retrieval when it breaks.
    "return a single leg's ranking unchanged" in {
      val ranking = Vector(mem("a"), mem("b"), mem("c"), mem("d"))
      val stage = FuseStage(recencyWeight = 0.0, reinforcementWeight = 0.0)
      stage.run(MemoryRetrievalState(lexical = ranking), ctx()).map { fused =>
        fused.ranked.map(_._id) should be(ranking.map(_._id))
      }
    }

    "rank a record present in both legs above one that is rank-1 in only one" in {
      val a = mem("in both")
      val b = mem("lexical only")
      val c = mem("vector rank one")
      val stage = FuseStage(lexicalWeight = 1.0, vectorWeight = 1.0, recencyWeight = 0.0, reinforcementWeight = 0.0)
      stage.run(MemoryRetrievalState(lexical = Vector(a, b), vectorHits = Vector(c, a)), ctx()).map { fused =>
        fused.ranked.head._id should be(a._id)
      }
    }

    "include records ranked by only one leg" in {
      val a = mem("x")
      val b = mem("y")
      val c = mem("z")
      val stage = FuseStage(recencyWeight = 0.0, reinforcementWeight = 0.0)
      stage.run(MemoryRetrievalState(lexical = Vector(a, b), vectorHits = Vector(c)), ctx()).map { fused =>
        fused.ranked.map(_._id) should contain allOf (a._id, b._id, c._id)
      }
    }

    "handle empty legs gracefully" in
      FuseStage().run(MemoryRetrievalState(), ctx()).map { fused =>
        fused.ranked should be(Vector.empty)
      }

    "let a low-confidence record lose to a high-confidence peer at identical ranks" in {
      val a = mem("alpha", confidence = 0.2)
      val b = mem("bravo", confidence = 1.0)
      val c = mem("charlie", confidence = 0.5)
      val ranking = Vector(a, b, c)
      val stage = FuseStage(lexicalWeight = 1.0, vectorWeight = 1.0, recencyWeight = 0.0, reinforcementWeight = 0.0)
      stage.run(MemoryRetrievalState(lexical = ranking, vectorHits = ranking), ctx()).map { fused =>
        val order = fused.ranked.map(_._id)
        order.indexOf(b._id) should be < order.indexOf(a._id)
        order.last should be(a._id)
      }
    }

    "reject a non-negative weight configuration at construction" in Task {
      an[IllegalArgumentException] should be thrownBy FuseStage(recencyWeight = -0.1)
      an[IllegalArgumentException] should be thrownBy FuseStage(reinforcementWeight = -1.0)
      an[IllegalArgumentException] should be thrownBy FuseStage(recencyHalfLifeMs = 0L)
    }
  }

  /**
   * Straight transcription of the weighted-RRF formula, independent of
   * the stage's accumulation, so the stage is checked against the
   * definition rather than against itself.
   */
  private def referenceRrf(legs: List[(Vector[ContextMemory], Double)], k: Int): List[Id[ContextMemory]] = {
    val accum = scala.collection.mutable.LinkedHashMap.empty[Id[ContextMemory], Double]
    legs.foreach { case (ranking, legWeight) =>
      ranking.iterator.zipWithIndex.foreach { case (m, idx) =>
        val contribution = m.confidence * legWeight / (k + idx + 1)
        accum.updateWith(m._id) {
          case Some(v) => Some(v + contribution)
          case None => Some(contribution)
        }
      }
    }
    accum.toList.sortBy { case (_, score) => -score }.map(_._1)
  }

  "RerankStage" should {
    val ranked = Vector(mem("first"), mem("second"), mem("third"))
    val state = MemoryRetrievalState(ranked = ranked)

    "apply a stub reranker's ordering" in {
      val reversing = new MemoryReranker {
        override def rerank(sigil: Sigil, query: String, memories: Vector[ContextMemory]): Task[Vector[ContextMemory]] =
          Task.pure(memories.reverse)
      }
      RerankStage(Some(reversing)).run(state, ctx()).map { out =>
        out.ranked.map(_.fact) should be(Vector("third", "second", "first"))
      }
    }

    "pass through unchanged when no reranker is configured" in
      RerankStage(None).run(state, ctx()).map { out =>
        out.ranked.map(_.fact) should be(Vector("first", "second", "third"))
      }

    "keep the fused order when the reranker fails" in {
      val failing = new MemoryReranker {
        override def rerank(sigil: Sigil, query: String, memories: Vector[ContextMemory]): Task[Vector[ContextMemory]] =
          Task.error(new RuntimeException("boom"))
      }
      RerankStage(Some(failing)).run(state, ctx()).map { out =>
        out.ranked.map(_.fact) should be(Vector("first", "second", "third"))
      }
    }

    "keep the fused order when the reranker drops records" in {
      val truncating = new MemoryReranker {
        override def rerank(sigil: Sigil, query: String, memories: Vector[ContextMemory]): Task[Vector[ContextMemory]] =
          Task.pure(memories.take(1))
      }
      RerankStage(Some(truncating)).run(state, ctx()).map { out =>
        out.ranked.map(_.fact) should be(Vector("first", "second", "third"))
      }
    }

    "keep the fused order when the reranker substitutes a record it was never given" in {
      val hallucinating = new MemoryReranker {
        override def rerank(sigil: Sigil, query: String, memories: Vector[ContextMemory]): Task[Vector[ContextMemory]] =
          Task.pure(memories.drop(1) :+ mem("invented"))
      }
      RerankStage(Some(hallucinating)).run(state, ctx()).map { out =>
        out.ranked.map(_.fact) should be(Vector("first", "second", "third"))
      }
    }
  }

  "BudgetStage" should {
    "keep the best-first prefix that fits the token budget" in {
      // HeuristicTokenizer ≈ 2/7 tokens per char: 70 chars ≈ 20 tokens.
      val big1 = mem("a" * 70)
      val big2 = mem("b" * 70)
      val big3 = mem("c" * 70)
      val state = MemoryRetrievalState(ranked = Vector(big1, big2, big3))
      BudgetStage(limit = 10, tokenBudget = Some(45)).run(state, ctx()).map { out =>
        out.ranked.map(_._id) should be(Vector(big1._id, big2._id))
      }
    }

    "always keep the top-ranked memory even when it alone exceeds the budget" in {
      val huge = mem("z" * 700)
      val small = mem("tiny")
      val state = MemoryRetrievalState(ranked = Vector(huge, small))
      BudgetStage(limit = 10, tokenBudget = Some(10)).run(state, ctx()).map { out =>
        out.ranked.map(_._id) should be(Vector(huge._id))
      }
    }

    "apply only the count cap when no token budget is set" in {
      val entries = Vector(mem("one"), mem("two"), mem("three"))
      BudgetStage(limit = 2).run(MemoryRetrievalState(ranked = entries), ctx()).map { out =>
        out.ranked.map(_._id) should be(entries.take(2).map(_._id))
      }
    }

    "exclude before the count cap so an excluded entry gives its slot back" in {
      val excluded = mem("pinned, already rendered")
      val a = mem("keep me")
      val b = mem("keep me too")
      val context = ctx().copy(exclude = Set(excluded._id))
      BudgetStage(limit = 2).run(MemoryRetrievalState(ranked = Vector(excluded, a, b)), context).map { out =>
        out.ranked.map(_._id) should be(Vector(a._id, b._id))
      }
    }
  }

  "RecallStage" should {
    "retrieve without throwing on a pathologically long query" in {
      TestSigil.reset()
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(MemoryTestSpace)))
      TestSigil.withDB(_.memories.transaction { tx =>
        tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
      }).sync()

      val stored = TestSigil.persistMemory(mem("The user's favorite color is blue.")).sync()
      // A pasted artefact rather than a question — one Lucene Should
      // clause per token would compile into a query the backend
      // rejects outright, taking the turn with it.
      val huge = (1 to 5000).map(i => s"token$i").mkString(" ") + " favorite color blue"
      val context = ctx().copy(query = huge, candidatePool = 10)
      RecallStage().run(MemoryRetrievalState(), context).map { state =>
        (state.lexical ++ state.vectorHits).map(_._id) should contain(stored._id)
      }
    }
  }

  "Record stage (end-to-end retrieve)" should {
    "bump accessCount and lastAccessedAt on surfaced memories once the accumulator flushes" in {
      TestSigil.reset()
      TestSigil.setEmbeddingProvider(TestHashEmbeddingProvider)
      TestSigil.setVectorIndex(new InMemoryVectorIndex)
      TestSigil.setAccessibleSpaces(_ => Task.pure(Set(MemoryTestSpace)))
      TestSigil.withDB(_.memories.transaction { tx =>
        tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
      }).sync()

      val stored = TestSigil.persistMemory(mem("The user's favorite color is blue.")).sync()
      val convId = Conversation.id(s"record-${rapid.Unique()}")
      val view = ConversationView(
        conversationId = convId,
        frames = Vector(ContextFrame.Text("What is my favorite color?", TestUser, Id[Event](s"q-${rapid.Unique()}")))
      )
      val retriever = StandardMemoryRetriever(limit = 3)

      // The Record stage fires on a background task and accumulates
      // in memory; the flush is what lands it on the row.
      def awaitBump(remaining: Int): Task[Int] =
        TestSigil.flushMemoryAccesses.flatMap { _ =>
          TestSigil.withDB(_.memories.transaction(_.get(stored._id))).flatMap {
            case Some(m) if m.accessCount > 0 => Task.pure(m.accessCount)
            case _ if remaining <= 0 => Task.pure(0)
            case _ => Task.sleep(100.millis).flatMap(_ => awaitBump(remaining - 1))
          }
        }

      for {
        result <- retriever.retrieve(TestSigil, convId, view.frames, List(TestUser, TestAgent))
        count <- awaitBump(remaining = 50)
      } yield {
        result.memories should contain(stored._id)
        count should be(1)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
