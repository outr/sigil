package spec

import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.Sigil
import sigil.conversation.{ConsolidationVerdict, ContextMemory, MemorySource}
import sigil.db.Model
import sigil.embedding.EmbeddingProvider
import sigil.event.Event
import sigil.maintenance.MemoryConsolidationTask
import sigil.tool.consult.ConsolidateMemoriesInput
import sigil.vector.InMemoryVectorIndex

import scala.collection.mutable.ListBuffer

/**
 * Coverage for [[MemoryConsolidationTask]]:
 *
 *   - clustering finds planted near-duplicates (controlled embedding
 *     vectors) and routes exactly them to the consult;
 *   - a Merge verdict writes a proper version chain — the merged
 *     record supersedes the cluster, the members stop being
 *     recallable, provenance unions;
 *   - KeepSeparate leaves every record untouched;
 *   - the per-sweep cluster cap bounds consult count;
 *   - the sweep no-ops without vector wiring.
 */
class MemoryConsolidationTaskSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  /**
   * Controlled embedder — facts about the same subject share a
   * direction (cosine > 0.99); different subjects are orthogonal.
   */
  private object ControlledEmbedding extends EmbeddingProvider {
    override val dimensions: Int = 4

    private def base(text: String): Array[Double] = {
      val lower = text.toLowerCase
      if (lower.contains("blue")) Array(1.0, 0.02, 0.0, 0.0)
      else if (lower.contains("biscuit")) Array(0.02, 0.0, 1.0, 0.0)
      else if (lower.contains("deploy")) Array(0.0, 1.0, 0.0, 0.0)
      else Array(0.0, 0.0, 0.0, 1.0)
    }

    override def embed(text: String): Task[Vector[Double]] = Task {
      val raw = base(text)
      // Tiny per-text perturbation keeps near-duplicates distinct
      // points while staying far above the 0.92 threshold.
      val jitter = math.abs(text.hashCode % 100) / 10000.0
      val v = raw.clone()
      v(3) += jitter
      val norm = math.sqrt(v.map(x => x * x).sum)
      v.map(_ / norm).toVector
    }

    override def embedBatch(texts: List[String]): Task[List[Vector[Double]]] =
      Task.sequence(texts.map(embed))
  }

  private class ScriptedConsolidation(verdictFor: List[ContextMemory] => Option[ConsolidateMemoriesInput],
                                      clusterCap: Int = 8,
                                      candidateCap: Int = 200)
    extends MemoryConsolidationTask(
      spaces = List(MemoryTestSpace),
      fallbackModelId = Id[Model]("test-model"),
      chain = List(TestAgent),
      maxClustersPerSweep = clusterCap,
      maxCandidatesPerSpace = candidateCap
    ) {
    val consulted: ListBuffer[List[ContextMemory]] = ListBuffer.empty

    override protected def consultCluster(host: Sigil, cluster: List[ContextMemory]): Task[Option[ConsolidateMemoriesInput]] = Task {
      consulted += cluster
      verdictFor(cluster)
    }
  }

  private def wire(): Unit = {
    TestSigil.reset()
    TestSigil.setEmbeddingProvider(ControlledEmbedding)
    TestSigil.setVectorIndex(new InMemoryVectorIndex)
    TestSigil.setAccessibleSpaces(_ => Task.pure(Set(MemoryTestSpace)))
    TestSigil.withDB(_.memories.transaction { tx =>
      tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
    }).sync()
  }

  private def seed(fact: String,
                   createdOffsetMs: Long = 0L,
                   sourceEventIds: List[Id[Event]] = Nil,
                   modeAffinity: Set[Id[sigil.provider.Mode]] = Set.empty,
                   memoryType: sigil.conversation.MemoryType = sigil.conversation.MemoryType.Fact,
                   expiresAt: Option[Timestamp] = None): Task[ContextMemory] =
    TestSigil.persistMemory(ContextMemory(
      fact = fact,
      label = fact.take(24),
      summary = fact,
      source = MemorySource.Compression,
      spaceId = MemoryTestSpace,
      keywords = Vector("test"),
      memoryType = memoryType,
      expiresAt = expiresAt,
      created = Timestamp(System.currentTimeMillis() - createdOffsetMs),
      modified = Timestamp(System.currentTimeMillis() - createdOffsetMs),
      modeAffinity = modeAffinity,
      sourceEventIds = sourceEventIds
    ))

  "MemoryConsolidationTask" should {
    "merge a planted near-duplicate cluster through the versioning machinery" in {
      wire()
      val e1 = Id[Event](s"c1-${rapid.Unique()}")
      val e2 = Id[Event](s"c2-${rapid.Unique()}")
      val older = seed("The user's favorite color is blue.", createdOffsetMs = 60_000, sourceEventIds = List(e1)).sync()
      val newer = seed("User prefers the color blue.", sourceEventIds = List(e2)).sync()
      seed("The deploy target is us-east-1.").sync()

      val task = new ScriptedConsolidation(_ =>
        Some(ConsolidateMemoriesInput(
          verdict = ConsolidationVerdict.Merge,
          mergedFact = Some("The user's favorite color is blue."),
          mergedLabel = Some("Favorite color")
        )))
      task.runOnce(TestSigil).flatMap { _ =>
        TestSigil.withDB(_.memories.transaction(_.list)).map { rows =>
          task.consulted.toList.map(_.map(_._id).toSet) should be(List(Set(older._id, newer._id)))

          val merged = rows.find(_.supersedes.contains(older._id)).getOrElse(fail("merged record missing"))
          merged.fact should be("The user's favorite color is blue.")
          merged.label should be("Favorite color")
          merged.sourceEventIds.toSet should be(Set(e1, e2))
          merged.isRecallable(Timestamp()) should be(true)

          val archivedOlder = rows.find(_._id == older._id).get
          val archivedNewer = rows.find(_._id == newer._id).get
          archivedOlder.validUntil.isDefined should be(true)
          archivedNewer.validUntil.isDefined should be(true)
          archivedOlder.supersededBy should be(Some(merged._id))
          archivedNewer.supersededBy should be(Some(merged._id))
          archivedOlder.isRecallable(Timestamp()) should be(false)
          archivedNewer.isRecallable(Timestamp()) should be(false)

          // Nothing hard-deleted; the untouched fact is untouched.
          rows.exists(m => m.fact.contains("us-east-1") && m.validUntil.isEmpty) should be(true)
        }
      }
    }

    "leave every record untouched on KeepSeparate" in {
      wire()
      seed("The user's favorite color is blue.", createdOffsetMs = 60_000).sync()
      seed("User prefers the color blue.").sync()

      val task = new ScriptedConsolidation(_ => Some(ConsolidateMemoriesInput(verdict = ConsolidationVerdict.KeepSeparate)))
      task.runOnce(TestSigil).flatMap { _ =>
        TestSigil.withDB(_.memories.transaction(_.list)).map { rows =>
          task.consulted.size should be(1)
          rows.size should be(2)
          rows.forall(_.validUntil.isEmpty) should be(true)
          rows.forall(_.supersededBy.isEmpty) should be(true)
        }
      }
    }

    "honor the per-sweep cluster cap" in {
      wire()
      seed("The user's favorite color is blue.", createdOffsetMs = 90_000).sync()
      seed("User prefers the color blue.", createdOffsetMs = 80_000).sync()
      seed("The user's dog is named Biscuit.", createdOffsetMs = 70_000).sync()
      seed("User's dog's name is Biscuit.", createdOffsetMs = 60_000).sync()

      val task = new ScriptedConsolidation(
        _ => Some(ConsolidateMemoriesInput(verdict = ConsolidationVerdict.KeepSeparate)),
        clusterCap = 1)
      task.runOnce(TestSigil).map { _ =>
        task.consulted.size should be(1)
      }
    }

    "never cluster memories whose mode affinity differs" in {
      wire()
      val coding = Id[sigil.provider.Mode]("coding")
      seed("The user's favorite color is blue.", createdOffsetMs = 60_000, modeAffinity = Set(coding)).sync()
      seed("User prefers the color blue.").sync()

      val task = new ScriptedConsolidation(_ => Some(ConsolidateMemoriesInput(verdict = ConsolidationVerdict.KeepSeparate)))
      task.runOnce(TestSigil).map { _ =>
        // Near-identical embeddings, but a mode-scoped record and a
        // universal one can't merge into a single scope.
        task.consulted.size should be(0)
      }
    }

    "carry mode affinity and memory type from the primary member onto the merged record" in {
      wire()
      val coding = Id[sigil.provider.Mode]("coding")
      val older = seed(
        "The user's favorite color is blue.",
        createdOffsetMs = 60_000,
        modeAffinity = Set(coding),
        memoryType = sigil.conversation.MemoryType.Preference).sync()
      seed(
        "User prefers the color blue.",
        modeAffinity = Set(coding),
        memoryType = sigil.conversation.MemoryType.Preference).sync()

      val task = new ScriptedConsolidation(_ =>
        Some(ConsolidateMemoriesInput(
          verdict = ConsolidationVerdict.Merge,
          mergedFact = Some("The user's favorite color is blue.")
        )))
      task.runOnce(TestSigil).flatMap { _ =>
        TestSigil.withDB(_.memories.transaction(_.list)).map { rows =>
          val merged = rows.find(_.supersedes.contains(older._id)).getOrElse(fail("merged record missing"))
          merged.modeAffinity should be(Set(coding))
          merged.memoryType should be(sigil.conversation.MemoryType.Preference)
        }
      }
    }

    "skip memories that carry an expiry" in {
      wire()
      val soon = Timestamp(System.currentTimeMillis() + 3_600_000L)
      seed("The user's favorite color is blue.", createdOffsetMs = 60_000, expiresAt = Some(soon)).sync()
      seed("User prefers the color blue.", expiresAt = Some(soon)).sync()

      val task = new ScriptedConsolidation(_ => Some(ConsolidateMemoriesInput(verdict = ConsolidationVerdict.KeepSeparate)))
      task.runOnce(TestSigil).map { _ =>
        task.consulted.size should be(0)
      }
    }

    "refuse a merge whose fact is not grounded in the cluster" in {
      wire()
      val older = seed("The user's favorite color is blue.", createdOffsetMs = 60_000).sync()
      val newer = seed("User prefers the color blue.").sync()

      val task = new ScriptedConsolidation(_ =>
        Some(ConsolidateMemoriesInput(
          verdict = ConsolidationVerdict.Merge,
          mergedFact = Some("Quarterly revenue exceeded projections across every regional territory.")
        )))
      task.runOnce(TestSigil).flatMap { _ =>
        TestSigil.withDB(_.memories.transaction(_.list)).map { rows =>
          task.consulted.size should be(1)
          rows.size should be(2)
          rows.map(_._id).toSet should be(Set(older._id, newer._id))
          rows.forall(_.validUntil.isEmpty) should be(true)
        }
      }
    }

    "refuse a merge whose fact is empty or an unbounded expansion" in {
      val cluster = List(
        ContextMemory(
          fact = "The deploy target is us-east-1.",
          label = "t",
          summary = "s",
          source = MemorySource.Compression,
          spaceId = MemoryTestSpace),
        ContextMemory(
          fact = "Deploys go to us-east-1.",
          label = "t",
          summary = "s",
          source = MemorySource.Compression,
          spaceId = MemoryTestSpace)
      )
      Task {
        sigil.maintenance.MemoryConsolidationTask.validateMerge(cluster, None).isLeft should be(true)
        sigil.maintenance.MemoryConsolidationTask.validateMerge(cluster, Some("   ")).isLeft should be(true)
        sigil.maintenance.MemoryConsolidationTask
          .validateMerge(cluster, Some("The deploy target is us-east-1. " * 30)).isLeft should be(true)
        sigil.maintenance.MemoryConsolidationTask
          .validateMerge(cluster, Some("The deploy target is us-east-1.")).isRight should be(true)
      }
    }

    "prefer the newest candidates so an unmergeable backlog cannot starve fresh duplicates" in {
      wire()
      // Two stale, unmergeable singletons plus a fresh near-duplicate
      // pair. With an oldest-first take of 2, the pair would never be
      // examined.
      seed("The deploy target is us-east-1.", createdOffsetMs = 900_000).sync()
      seed("The user's dog is named Biscuit.", createdOffsetMs = 800_000).sync()
      val a = seed("The user's favorite color is blue.", createdOffsetMs = 2_000).sync()
      val b = seed("User prefers the color blue.", createdOffsetMs = 1_000).sync()

      val task = new ScriptedConsolidation(
        _ => Some(ConsolidateMemoriesInput(verdict = ConsolidationVerdict.KeepSeparate)),
        candidateCap = 2)
      task.runOnce(TestSigil).map { _ =>
        task.consulted.toList.map(_.map(_._id).toSet) should be(List(Set(a._id, b._id)))
      }
    }

    "no-op with a debug log when vector search is not wired" in {
      TestSigil.reset()
      TestSigil.withDB(_.memories.transaction { tx =>
        tx.list.flatMap(rows => Task.sequence(rows.map(r => tx.delete(r._id))).unit)
      }).sync()
      seed("The user's favorite color is blue.").sync()
      seed("User prefers the color blue.").sync()

      val task = new ScriptedConsolidation(_ => Some(ConsolidateMemoriesInput(verdict = ConsolidationVerdict.KeepSeparate)))
      task.runOnce(TestSigil).map { _ =>
        task.consulted.size should be(0)
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
