package sigil.maintenance

import lightdb.filter.*
import rapid.Task
import sigil.Sigil
import sigil.conversation.ContextMemory
import sigil.embedding.EmbeddingRef
import sigil.vector.NoOpVectorIndex

import scala.concurrent.duration.*

/**
 * Reconciles the memory store against [[sigil.vector.VectorIndex]].
 *
 * Every framework write path stamps a [[EmbeddingRef]] onto the record
 * it indexes and clears it when it evicts the point, so a record whose
 * stamp doesn't describe its current `fact` — or was built by a
 * different embedder — is provably out of sync with the index. This
 * sweep finds those records, re-embeds them, and restamps. It repairs
 * the three ways drift happens in practice: an embedding call that
 * failed and was swallowed, an app-side write that reached the store
 * without going through an indexing path, and a change of embedding
 * model or dimensionality.
 *
 * The scan is a single indexed query — `embeddingReconcilable === true
 * && embeddingIdentity !== <live provider identity>` — over two fields
 * [[ContextMemory]] projects at write time. When nothing has drifted it
 * matches zero documents, so a steady-state sweep costs one empty index
 * lookup and no reads; that is what makes it cheap enough to ship in
 * the default [[Sigil.maintenanceTasks]]. Work is capped at
 * [[maxPerSweep]] records, so a bulk model migration drains over
 * several sweeps instead of issuing one unbounded burst of embedding
 * calls.
 *
 * Requires vector wiring ([[Sigil.embeddingProvider]] +
 * [[Sigil.vectorIndex]]); without it the sweep no-ops with a debug log
 * and never touches the store.
 */
case class EmbeddingReconcileTask(override val interval: FiniteDuration = 1.hour,
                                  maxPerSweep: Int = EmbeddingReconcileTask.DefaultMaxPerSweep,
                                  override val runImmediatelyOnStart: Boolean = false)
  extends MaintenanceTask {

  override def name: String = "embedding-reconcile"

  override def runOnce(host: Sigil): Task[Unit] =
    if (!vectorReady(host)) Task(scribe.debug(s"$name: vector search not wired — skipping sweep"))
    else drifted(host).flatMap {
      case Nil => Task.unit
      case rows => reconcile(host, rows)
    }

  private def vectorReady(host: Sigil): Boolean =
    host.embeddingProvider.dimensions > 0 && (host.vectorIndex ne NoOpVectorIndex)

  /**
   * Recallable records whose stored point doesn't match the text they
   * currently carry, embedded by the embedder currently wired.
   */
  private def drifted(host: Sigil): Task[List[ContextMemory]] = {
    val expected = EmbeddingRef.identityOf(host.embeddingProvider)
    host.withDB(_.memories.transaction { tx =>
      tx.query
        .filter(m => (m.embeddingReconcilable === true) && (m.embeddingIdentity !== expected))
        .limit(maxPerSweep)
        .toList
    })
  }

  /**
   * Re-embed and restamp each drifted record, then drop cached
   * retrievals so the repaired points are reachable on the next turn.
   * A record that fails to re-index keeps its stale stamp and is
   * retried on the following sweep.
   */
  private def reconcile(host: Sigil, rows: List[ContextMemory]): Task[Unit] =
    Task.sequence(rows.map { m =>
      host.reindexMemory(m).handleError { e =>
        Task {
          scribe.warn(s"$name: re-index failed for memory ${m._id.value}: ${e.getMessage}")
          m
        }
      }
    }).map { _ =>
      host.invalidateAllMemoryRetrievals()
      scribe.info(s"$name: re-embedded ${rows.size} memories whose vector points had drifted")
    }
}

object EmbeddingReconcileTask {

  /**
   * Records repaired per sweep. Bounds the embedding spend a single
   * tick can incur when a whole corpus needs re-embedding after a
   * model change.
   */
  val DefaultMaxPerSweep: Int = 200
}
