package spec

import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextMemory, MemorySource, MemoryStatus}
import sigil.embedding.{EmbeddingProvider, EmbeddingRef, NoOpEmbeddingProvider}
import sigil.maintenance.EmbeddingReconcileTask
import sigil.vector.{InMemoryVectorIndex, NoOpVectorIndex}

import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage for the vector-point provenance stamp
 * ([[ContextMemory.embedding]]) and the drift sweep that repairs it
 * ([[EmbeddingReconcileTask]]):
 *
 *   - every write path stamps model / dimensions / content hash;
 *   - eviction clears the stamp so it never outlives its point;
 *   - a fact mutated behind the framework's back is detected and
 *     re-embedded;
 *   - swapping the embedding model re-embeds the whole corpus;
 *   - an in-sync store costs the sweep zero embedding calls;
 *   - the sweep no-ops without vector wiring.
 */
class EmbeddingProvenanceSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /** Deterministic embedder with a declared identity and a call
    * counter, so a spec can assert exactly how much embedding a sweep
    * spent. */
  private class CountingEmbedding(override val id: String, override val dimensions: Int = 4) extends EmbeddingProvider {
    val calls: AtomicInteger = new AtomicInteger(0)

    override def embed(text: String): Task[Vector[Double]] = Task {
      calls.incrementAndGet()
      val h = text.hashCode
      val raw = Vector.tabulate(dimensions)(i => ((h >> (i * 4)) & 0xf).toDouble + 1.0)
      val n = math.sqrt(raw.map(x => x * x).sum)
      raw.map(_ / n)
    }

    override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
      Task.sequence(texts.map(embed))
  }

  private def wire(provider: EmbeddingProvider): Unit = {
    TestSigil.reset()
    TestSigil.setEmbeddingProvider(provider)
    TestSigil.setVectorIndex(new InMemoryVectorIndex)
    TestSigil.setAccessibleSpaces(_ => Task.pure(Set(MemoryTestSpace)))
    clearMemories()
  }

  private def wireUnvectored(): Unit = {
    TestSigil.reset()
    TestSigil.setEmbeddingProvider(NoOpEmbeddingProvider)
    TestSigil.setVectorIndex(NoOpVectorIndex)
    clearMemories()
  }

  private def clearMemories(): Unit =
    TestSigil.withDB(_.memories.transaction { tx =>
      tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
    }).sync()

  private def memory(fact: String, key: Option[String] = None): ContextMemory = ContextMemory(
    fact = fact,
    label = fact.take(24),
    summary = fact,
    source = MemorySource.Compression,
    spaceId = MemoryTestSpace,
    key = key,
    keywords = Vector("test")
  )

  private def reload(m: ContextMemory): Task[ContextMemory] =
    TestSigil.withDB(_.memories.transaction(_.get(m._id))).map(_.getOrElse(fail(s"memory ${m._id.value} missing")))

  /** Write straight to the store, bypassing every indexing path — the
    * shape of an app-side write that leaves the vector index behind. */
  private def rawUpsert(m: ContextMemory): Task[ContextMemory] =
    TestSigil.withDB(_.memories.transaction(_.upsert(m)))

  "the embedding provenance stamp" should {
    "record model, dimensions, and content hash on persist" in {
      wire(new CountingEmbedding("test-embed-v1"))
      val stored = TestSigil.persistMemory(memory("The deploy target is us-east-1.")).sync()

      stored.embedding.map(_.model) should be(Some("test-embed-v1"))
      stored.embedding.map(_.dimensions) should be(Some(4))
      stored.embedding.map(_.contentHash) should be(Some(EmbeddingRef.hash("The deploy target is us-east-1.")))

      reload(stored).map { row =>
        row.embedding should be(stored.embedding)
        row.embeddingIdentity should be("test-embed-v1/4")
        row.embeddingReconcilable should be(true)
      }
    }

    "stamp the batched persist path and the keyed upsert path" in {
      wire(new CountingEmbedding("test-embed-v1"))
      val batched = TestSigil.persistMemories(List(memory("Fact one."), memory("Fact two."))).sync()
      val keyed = TestSigil.upsertMemoryByKey(memory("Fact three.", key = Some("k.three"))).sync().memory

      batched.map(_.embedding.map(_.contentHash)) should be(
        List(Some(EmbeddingRef.hash("Fact one.")), Some(EmbeddingRef.hash("Fact two."))))
      keyed.embedding.map(_.contentHash) should be(Some(EmbeddingRef.hash("Fact three.")))

      Task.sequence((batched :+ keyed).map(reload)).map { rows =>
        rows.map(_.embeddingIdentity).toSet should be(Set("test-embed-v1/4"))
      }
    }

    "clear when the record's point is evicted" in {
      wire(new CountingEmbedding("test-embed-v1"))
      val stored = TestSigil.persistMemory(memory("Temporary detail.")).sync()
      stored.embedding.isDefined should be(true)

      val rejected = TestSigil.rejectMemory(stored._id).sync().getOrElse(fail("reject returned None"))
      reload(rejected).map { row =>
        row.status should be(MemoryStatus.Rejected)
        row.embedding should be(None)
        row.embeddingReconcilable should be(false)
      }
    }
  }

  "EmbeddingReconcileTask" should {
    "re-embed and restamp a record whose fact drifted behind the framework's back" in {
      val provider = new CountingEmbedding("test-embed-v1")
      wire(provider)
      val stored = TestSigil.persistMemory(memory("The build runs nightly.")).sync()
      val drifted = rawUpsert(stored.copy(fact = "The build runs hourly.")).sync()
      drifted.embeddingIdentity should be(EmbeddingRef.Unindexed)

      val before = provider.calls.get()
      EmbeddingReconcileTask().runOnce(TestSigil).sync()

      reload(stored).map { row =>
        provider.calls.get() should be(before + 1)
        row.embedding.map(_.contentHash) should be(Some(EmbeddingRef.hash("The build runs hourly.")))
        row.embeddingIdentity should be("test-embed-v1/4")
      }
    }

    "re-embed the corpus after the embedding model changes" in {
      wire(new CountingEmbedding("test-embed-v1"))
      val a = TestSigil.persistMemory(memory("Alpha fact.")).sync()
      val b = TestSigil.persistMemory(memory("Beta fact.")).sync()

      val next = new CountingEmbedding("test-embed-v2")
      TestSigil.setEmbeddingProvider(next)
      EmbeddingReconcileTask().runOnce(TestSigil).sync()

      Task.sequence(List(a, b).map(reload)).map { rows =>
        next.calls.get() should be(2)
        rows.map(_.embedding.map(_.model)).toSet should be(Set(Some("test-embed-v2")))
        rows.map(_.embeddingIdentity).toSet should be(Set("test-embed-v2/4"))
      }
    }

    "spend nothing when every point is already current" in {
      val provider = new CountingEmbedding("test-embed-v1")
      wire(provider)
      TestSigil.persistMemory(memory("Stable fact one.")).sync()
      TestSigil.persistMemory(memory("Stable fact two.")).sync()

      val before = provider.calls.get()
      EmbeddingReconcileTask().runOnce(TestSigil).sync()
      Task(provider.calls.get() should be(before))
    }

    "leave archived and expired records alone" in {
      val provider = new CountingEmbedding("test-embed-v1")
      wire(provider)
      val expiring = TestSigil.persistMemory(
        memory("Expiring fact.").copy(expiresAt = Some(Timestamp(System.currentTimeMillis() + 3_600_000)))
      ).sync()
      val keyed = TestSigil.upsertMemoryByKey(memory("Original wording.", key = Some("k.arch"))).sync().memory
      TestSigil.upsertMemoryByKey(memory("Replacement wording.", key = Some("k.arch"))).sync()

      // Both now read as holding no usable point: the expiring one
      // because a raw mutation left its stamp describing text it no
      // longer carries, the archived one because versioning evicted
      // its point and cleared the stamp outright.
      rawUpsert(expiring.copy(fact = "Expiring fact, revised.")).sync()

      val before = provider.calls.get()
      EmbeddingReconcileTask().runOnce(TestSigil).sync()

      Task.sequence(List(expiring, keyed).map(reload)).map { rows =>
        provider.calls.get() should be(before)
        rows.map(_.embeddingIdentity).toSet should be(Set(EmbeddingRef.Unindexed))
        rows.map(_.embeddingReconcilable) should be(List(false, false))
        rows.last.embedding should be(None)
      }
    }

    "bound the number of records repaired per sweep" in {
      val provider = new CountingEmbedding("test-embed-v1")
      wire(provider)
      val stored = (1 to 5).toList.map(i => TestSigil.persistMemory(memory(s"Bounded fact $i.")).sync())
      Task.sequence(stored.map(m => rawUpsert(m.copy(fact = s"${m.fact} revised")))).sync()

      val before = provider.calls.get()
      EmbeddingReconcileTask(maxPerSweep = 2).runOnce(TestSigil).sync()
      Task(provider.calls.get() should be(before + 2))
    }

    "no-op when vector search isn't wired" in {
      wireUnvectored()
      val stored = TestSigil.persistMemory(memory("Unvectored fact.")).sync()
      stored.embedding should be(None)

      EmbeddingReconcileTask().runOnce(TestSigil).sync()
      reload(stored).map(_.embedding should be(None))
    }
  }
}
