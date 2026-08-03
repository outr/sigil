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

  /** Keyless, unpinned, Approved, recallable, non-expiring memories in
    * the space, newest first, capped at [[maxCandidatesPerSpace]].
    *
    * Memories with `expiresAt` set are excluded: consolidating a fact
    * that is scheduled to stop mattering into a merged record that
    * never expires launders a temporary fact into a permanent one.
    *
    * Newest-first matters when the space holds more than
    * [[maxCandidatesPerSpace]] rows. Under an oldest-first take the
    * same head of the corpus is re-examined every sweep — if it holds
    * no mergeable pairs, nothing beyond it is ever looked at, and
    * fresh duplicates (which is where duplicates actually come from)
    * never get a turn. Ordering by `modified` also gives a record
    * touched by a keyed refresh another pass. */
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
        .filter(m => !m.key.exists(_.nonEmpty) && m.fact.nonEmpty && m.expiresAt.isEmpty && m.isRecallable(now))
        .sortBy(m => -m.modified.value)
        .take(maxCandidatesPerSpace)
    }

  /** Greedy near-duplicate clustering: walk candidates in stable
    * (newest-first) order; each unvisited seed vector-searches the
    * space and pulls in unvisited candidates at cosine ≥
    * [[similarityThreshold]] that share the seed's exact
    * [[ContextMemory.modeAffinity]]. Clusters need ≥ 2 members; at
    * most `budget` clusters are produced.
    *
    * Identical mode affinity is a hard clustering constraint, not a
    * merge-time reconciliation: a directive scoped to one mode and a
    * near-identical universal fact state the same thing in different
    * scopes, and any single merged record either escalates the scoped
    * one to universal (it now fires in every mode) or demotes the
    * universal one (it stops firing where it used to). */
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
              .filter(m => m.modeAffinity == seed.modeAffinity)
              .take(maxClusterSize)
            if (members.size >= 2) loop(rest, visited ++ members.map(_._id), members :: acc)
            else loop(rest, visited + seed._id, acc)
          }
      }

    loop(embedded, Set.empty, Nil)
  }

  /** Consult the cluster and apply the verdict. Failures are logged
    * and swallowed — one bad cluster never aborts the sweep. A Merge
    * whose proposed fact fails [[MemoryConsolidationTask.validateMerge]]
    * degrades to KeepSeparate: the sweep archives real user facts, so
    * a hallucinated or degenerate merge is the one outcome worth
    * refusing outright. */
  private def consolidate(host: Sigil, space: SpaceId, cluster: List[ContextMemory]): Task[Unit] =
    consultCluster(host, cluster)
      .flatMap {
        case Some(input) if input.verdict == ConsolidationVerdict.Merge =>
          MemoryConsolidationTask.validateMerge(cluster, input.mergedFact) match {
            case Right(_) => applyMerge(host, space, cluster, input)
            case Left(reason) => Task(scribe.warn(
              s"$name: rejecting merge in space ${space.value} ($reason) — keeping ${cluster.size} records separate"))
          }
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
    * standard versioning fields. The merged record inherits the
    * cluster's shared `modeAffinity` (identical by construction — see
    * [[buildClusters]]) and the primary member's `memoryType`, so a
    * consolidation never silently widens a memory's retrieval gate or
    * reclassifies what kind of thing it is.
    *
    * Archiving goes through [[Sigil.updateMemory]], which deletes the
    * member's vector point rather than re-embedding it — an archived
    * record must not stay reachable through the semantic leg. */
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
      memoryType = primary.memoryType,
      keywords = cluster.iterator.flatMap(_.keywords).toVector.distinct,
      confidence = cluster.iterator.map(_.confidence).max,
      validFrom = Some(now),
      supersedes = Some(primary._id),
      justification = Some(s"Consolidated from ${cluster.size} near-duplicate memories"),
      conversationId = cluster.map(_.conversationId).distinct match {
        case one :: Nil => one
        case _          => None
      },
      modeAffinity = primary.modeAffinity,
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
  /** Ceiling on the merged fact's length as a multiple of the combined
    * member facts — a merge that says MORE than its inputs did is
    * elaboration, not consolidation. */
  val MaxMergeLengthFactor: Int = 2

  /** Minimum share of some single member's content words that must
    * survive into the merged fact. A merge grounded in the cluster
    * restates one member closely; a fabrication shares almost nothing
    * with any of them. */
  val MinMergeOverlap: Double = 0.3

  /** Words this short or shorter are structural (articles,
    * prepositions, conjunctions) and carry no grounding signal. */
  private val MaxStructuralWordLength: Int = 3

  /**
   * Check a consult's proposed merged fact against the cluster it
   * claims to consolidate. `Right(fact)` when the merge is safe to
   * apply; `Left(reason)` describes why it was refused.
   *
   * Applying a merge archives every member, so an unusable merged
   * fact doesn't just add noise — it destroys the recallability of
   * facts the user actually stated. Three cheap structural checks
   * catch the failure modes a small summarization-tier model
   * exhibits: an empty or whitespace `mergedFact`, an "expansion"
   * that elaborates well past its inputs, and a fact whose content
   * words overlap none of the members (a confabulation, or the model
   * answering some other cluster).
   */
  def validateMerge(cluster: List[ContextMemory], mergedFact: Option[String]): Either[String, String] = {
    val fact = mergedFact.map(_.trim).getOrElse("")
    if (fact.isEmpty) Left("merged fact is empty")
    else {
      val combinedLength = cluster.iterator.map(_.fact.trim.length).sum
      if (combinedLength > 0 && fact.length > combinedLength * MaxMergeLengthFactor)
        Left(s"merged fact is ${fact.length} chars against $combinedLength of member facts")
      else {
        val mergedWords = contentWords(fact)
        val bestOverlap = cluster.iterator.map { m =>
          val memberWords = contentWords(m.fact)
          if (memberWords.isEmpty) 0.0
          else memberWords.count(mergedWords.contains).toDouble / memberWords.size
        }.maxOption.getOrElse(0.0)
        if (bestOverlap < MinMergeOverlap)
          Left(f"merged fact shares only ${bestOverlap * 100}%.0f%% of any member's content words")
        else Right(fact)
      }
    }
  }

  private def contentWords(text: String): Set[String] =
    text.toLowerCase.split("[^a-z0-9]+").iterator
      .filter(_.length > MaxStructuralWordLength)
      .toSet

  val SystemPrompt: String =
    """You curate an agent framework's long-term memory store. You'll be shown a small cluster of
      |memories whose embeddings are nearly identical. Decide whether they state the same underlying
      |fact (merge into one self-contained record) or genuinely distinct facts (keep separate).
      |Never invent detail; when merging, preserve every identifier, number, and URL verbatim.
      |When unsure, keep separate.""".stripMargin
}
