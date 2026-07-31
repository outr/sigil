package sigil.maintenance

import lightdb.filter.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.{Sigil, SpaceId}
import sigil.conversation.{ConsolidationVerdict, ContextMemory, MemorySource, MemoryStatus}
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.consult.{ConsolidateMemoriesInput, ConsolidateMemoriesTool, ConsultTool}
import sigil.vector.{NoOpVectorIndex, VectorQueryFilter}

import scala.concurrent.duration.*

/**
 * Periodic consolidation sweep — the difference between a memory store
 * that grows monotonically noisier and one that self-curates. Per
 * configured space:
 *
 *   1. Loads the keyless, unpinned, recallable memories (keyed
 *      memories already dedup through `upsertMemoryByKey` versioning;
 *      pinned criticals are curated deliberately).
 *   2. Embeds them and greedily clusters near-duplicates through the
 *      host's vector index (cosine ≥ [[similarityThreshold]]).
 *   3. Routes each cluster through a cheap-tier consult
 *      ([[ConsolidateMemoriesTool]], routed by `SummarizationWork`)
 *      for a typed merge / keep-separate verdict.
 *   4. Applies merges via the existing versioning machinery: a new
 *      merged record supersedes every member (`supersedes` → the
 *      oldest member; each member gets `validUntil` +
 *      `supersededBy` → the merged record), so history is preserved,
 *      nothing is hard-deleted, and the standard recall gate hides
 *      the superseded members from every retrieval surface.
 *      Keep-separate leaves every record untouched.
 *
 * Vector wiring ([[Sigil.embeddingProvider]] + [[Sigil.vectorIndex]])
 * is required — without it the sweep no-ops with a debug log.
 * [[maxClustersPerSweep]] caps consults per sweep so LLM cost is
 * bounded whatever the backlog.
 *
 * NOT in the default [[Sigil.maintenanceTasks]] — like
 * `memoryClassifierModel`, the machinery ships complete and activation
 * is an app decision (it spends LLM calls and rewrites memory rows).
 * Apps opt in by appending:
 *
 * {{{
 * override def maintenanceTasks: List[MaintenanceTask] =
 *   super.maintenanceTasks :+ new MemoryConsolidationTask(
 *     spaces          = List(UserSpace(userId)),
 *     fallbackModelId = cheapModelId,
 *     chain           = List(assistantId)
 *   )
 * }}}
 */
class MemoryConsolidationTask(spaces: List[SpaceId],
                              fallbackModelId: Id[Model],
                              chain: List[ParticipantId],
                              override val interval: FiniteDuration = 6.hours,
                              similarityThreshold: Double = 0.92,
                              maxClustersPerSweep: Int = 8,
                              maxClusterSize: Int = 6,
                              maxCandidatesPerSpace: Int = 200,
                              override val runImmediatelyOnStart: Boolean = false) extends MaintenanceTask {

  override def name: String = "memory-consolidation"

  override def runOnce(host: Sigil): Task[Unit] =
    if (!vectorReady(host)) Task(scribe.debug(s"$name: vector search not wired — skipping sweep"))
    else spaces
      .foldLeft(Task.pure(maxClustersPerSweep)) { (budgetTask, space) =>
        budgetTask.flatMap { budget =>
          if (budget <= 0) Task.pure(budget)
          else sweepSpace(host, space, budget).map(consulted => budget - consulted)
        }
      }
      .unit

  private def vectorReady(host: Sigil): Boolean =
    host.embeddingProvider.dimensions > 0 && (host.vectorIndex ne NoOpVectorIndex)

  /** Sweep one space under the remaining cluster budget; returns the
    * number of clusters consulted. */
  private def sweepSpace(host: Sigil, space: SpaceId, budget: Int): Task[Int] =
    candidates(host, space).flatMap { pool =>
      if (pool.size < 2) Task.pure(0)
      else host.embeddingProvider.embedBatch(pool.map(_.fact)).flatMap { vectors =>
        buildClusters(host, space, pool.zip(vectors), budget).flatMap { clusters =>
          Task.sequence(clusters.map(consolidate(host, space, _))).map(_ => clusters.size)
        }
      }
    }

  /** Keyless, unpinned, Approved, recallable memories in the space —
    * oldest first, capped at [[maxCandidatesPerSpace]]. */
  private def candidates(host: Sigil, space: SpaceId): Task[List[ContextMemory]] =
    host.withDB(_.memories.transaction { tx =>
      tx.query
        .filter(m =>
          (m.spaceIdValue === space.value) &&
            (m.pinned === false) &&
            (m.statusName === MemoryStatus.Approved.toString))
        .toList
    }).map { rows =>
      val now = Timestamp()
      rows
        .filter(m => !m.key.exists(_.nonEmpty) && m.fact.nonEmpty && m.isRecallable(now))
        .sortBy(_.created.value)
        .take(maxCandidatesPerSpace)
    }

  /** Greedy near-duplicate clustering: walk candidates in stable
    * (oldest-first) order; each unvisited seed vector-searches the
    * space and pulls in unvisited candidates at cosine ≥
    * [[similarityThreshold]]. Clusters need ≥ 2 members; at most
    * `budget` clusters are produced. */
  private def buildClusters(host: Sigil,
                            space: SpaceId,
                            embedded: List[(ContextMemory, Vector[Double])],
                            budget: Int): Task[List[List[ContextMemory]]] = {
    val byId = embedded.iterator.map { case (m, _) => m._id -> m }.toMap
    val filter = VectorQueryFilter(exact = Map("kind" -> "memory", "spaceId" -> space.value))

    def loop(pending: List[(ContextMemory, Vector[Double])],
             visited: Set[Id[ContextMemory]],
             acc: List[List[ContextMemory]]): Task[List[List[ContextMemory]]] =
      pending match {
        case Nil                                     => Task.pure(acc.reverse)
        case _ if acc.size >= budget                 => Task.pure(acc.reverse)
        case (seed, _) :: rest if visited(seed._id)  => loop(rest, visited, acc)
        case (seed, vec) :: rest =>
          host.vectorIndex.search(vec, limit = maxClusterSize * 2, filter = filter).flatMap { hits =>
            val matched = hits.iterator
              .filter(_.score >= similarityThreshold)
              .flatMap(_.payload.get("memoryId"))
              .map(Id[ContextMemory](_))
              .toList
            val members = (seed._id :: matched).distinct
              .flatMap(byId.get)
              .filterNot(m => m._id != seed._id && visited(m._id))
              .take(maxClusterSize)
            if (members.size >= 2) loop(rest, visited ++ members.map(_._id), members :: acc)
            else loop(rest, visited + seed._id, acc)
          }
      }

    loop(embedded, Set.empty, Nil)
  }

  /** Consult the cluster and apply the verdict. Failures are logged
    * and swallowed — one bad cluster never aborts the sweep. */
  private def consolidate(host: Sigil, space: SpaceId, cluster: List[ContextMemory]): Task[Unit] =
    consultCluster(host, cluster)
      .flatMap {
        case Some(input) if input.verdict == ConsolidationVerdict.Merge && input.mergedFact.exists(_.trim.nonEmpty) =>
          applyMerge(host, space, cluster, input)
        case _ => Task.unit
      }
      .handleError { e =>
        Task(scribe.warn(s"$name: cluster consolidation failed in space ${space.value}: ${e.getMessage}"))
      }

  /** The verdict consult — routed through the cheap summarization tier
    * via [[ConsultTool.invokeRouted]]. Protected so specs can script
    * verdicts without an LLM. */
  protected def consultCluster(host: Sigil, cluster: List[ContextMemory]): Task[Option[ConsolidateMemoriesInput]] = {
    val rendered = cluster.zipWithIndex.map { case (m, idx) =>
      s"${idx + 1}. [${m.label}] ${m.fact}"
    }.mkString("\n")
    ConsultTool.invokeRouted[ConsolidateMemoriesInput](
      sigil = host,
      tool = ConsolidateMemoriesTool,
      chain = chain,
      fallbackModelId = fallbackModelId,
      systemPrompt = MemoryConsolidationTask.SystemPrompt,
      userPrompt =
        s"""Near-duplicate memory cluster:
           |$rendered
           |
           |Return the consolidation verdict via the `consolidate_memories` tool.""".stripMargin
    )
  }

  /** Write the merged record and archive every member through the
    * standard versioning fields. */
  private def applyMerge(host: Sigil,
                         space: SpaceId,
                         cluster: List[ContextMemory],
                         input: ConsolidateMemoriesInput): Task[Unit] = {
    val now = Timestamp()
    val primary = cluster.minBy(_.created.value)
    val fact = input.mergedFact.map(_.trim).getOrElse(primary.fact)
    val merged = ContextMemory(
      fact = fact,
      label = input.mergedLabel.map(_.trim).filter(_.nonEmpty).getOrElse(primary.label),
      summary = fact,
      source = MemorySource.Compression,
      spaceId = space,
      keywords = cluster.iterator.flatMap(_.keywords).toVector.distinct,
      confidence = cluster.iterator.map(_.confidence).max,
      validFrom = Some(now),
      supersedes = Some(primary._id),
      justification = Some(s"Consolidated from ${cluster.size} near-duplicate memories"),
      conversationId = cluster.map(_.conversationId).distinct match {
        case one :: Nil => one
        case _          => None
      },
      sourceEventIds = cluster.flatMap(_.sourceEventIds).distinct
    )
    host.persistMemory(merged).flatMap { stored =>
      Task.sequence(cluster.map { member =>
        host.updateMemory(member.copy(validUntil = Some(now), supersededBy = Some(stored._id)))
      }).unit
    }
  }
}

object MemoryConsolidationTask {
  val SystemPrompt: String =
    """You curate an agent framework's long-term memory store. You'll be shown a small cluster of
      |memories whose embeddings are nearly identical. Decide whether they state the same underlying
      |fact (merge into one self-contained record) or genuinely distinct facts (keep separate).
      |Never invent detail; when merging, preserve every identifier, number, and URL verbatim.
      |When unsure, keep separate.""".stripMargin
}
