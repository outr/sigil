package sigil.conversation.compression.retrieval

import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.conversation.ContextMemory
import sigil.vector.TemporalBoost

/**
 * Fuse — combines the gated legs into one ranked list via
 * confidence-weighted Reciprocal Rank Fusion, then scales each fused
 * score by bounded recency and reinforcement terms:
 *
 * {{{
 * base(m)  = Σ over legs of confidence(m) × legWeight / (rrfK + rank)
 * score(m) = base(m) × (1 + recencyWeight × recency(m) + reinforcementWeight × reinforcement(m))
 * }}}
 *
 *   - `recency(m)` ∈ (0, 1] — [[TemporalBoost.decay]]'s
 *     exponential-decay curve over the memory's age
 *     (`now − max(created, modified)`); 1.0 at age zero, 0.5 at one
 *     [[recencyHalfLifeMs]].
 *   - `reinforcement(m)` ∈ [0, 1) — log-dampened access frequency,
 *     `ln(1 + accessCount) / (1 + ln(1 + accessCount))`; 0 for a
 *     never-accessed memory, asymptotically 1 for a hot one — a hot
 *     memory cannot monopolize because the term saturates.
 *
 * The multiplier is bounded to
 * `[1, 1 + recencyWeight + reinforcementWeight]`. At the defaults
 * (8% combined headroom) that is a bounded REORDER, not a pure
 * tiebreak: RRF base scores of adjacent ranks differ by roughly
 * `1/(k+r) − 1/(k+r+1)`, so at `rrfK = 60` the band can lift a
 * memory past a handful of neighbours — call it ~5 adjacent ranks —
 * but never across the list. Entries whose base scores differ by more
 * than the band never reorder, and both weights at `0.0` reproduce
 * pure confidence-weighted RRF ordering exactly.
 */
case class FuseStage(rrfK: Int = 60,
                     lexicalWeight: Double = 2.0,
                     vectorWeight: Double = 1.0,
                     /** Per-signal weight on the context keyword leg
                       * (classifier keywords + topic label). Default
                       * 1.0 — context should inform the ranking, never
                       * outvote a direct match on the user's question
                       * (whose lexical leg carries 2.0). `0.0` disables
                       * the context contribution entirely. */
                     keywordWeight: Double = 1.0,
                     recencyWeight: Double = FuseStage.DefaultRecencyWeight,
                     reinforcementWeight: Double = FuseStage.DefaultReinforcementWeight,
                     recencyHalfLifeMs: Long = FuseStage.DefaultRecencyHalfLifeMs) extends MemoryRetrievalStage {
  require(recencyWeight >= 0.0, "recencyWeight must be non-negative")
  require(reinforcementWeight >= 0.0, "reinforcementWeight must be non-negative")
  require(recencyHalfLifeMs > 0, "recencyHalfLifeMs must be positive")

  override val name: String = "fuse"

  override def run(state: MemoryRetrievalState, ctx: MemoryRetrievalContext): Task[MemoryRetrievalState] = Task {
    state.copy(ranked = fuse(
      List(
        state.lexical -> lexicalWeight,
        state.vectorHits -> vectorWeight,
        state.keywordHits -> keywordWeight
      ),
      ctx.now
    ))
  }

  /** Accumulate weighted-RRF contributions per record (insertion
    * order = first leg first, so tie ordering matches the legacy
    * id-based fuse), apply the bounded boost multiplier, and sort by
    * descending score (stable). */
  private[retrieval] def fuse(legs: List[(Vector[ContextMemory], Double)], now: Timestamp): Vector[ContextMemory] = {
    val accum = scala.collection.mutable.LinkedHashMap.empty[Id[ContextMemory], (ContextMemory, Double)]
    legs.foreach { case (ranking, legWeight) =>
      ranking.iterator.zipWithIndex.foreach { case (m, idx) =>
        val rank = idx + 1
        val contribution = m.confidence * legWeight / (rrfK + rank)
        accum.updateWith(m._id) {
          case Some((record, score)) => Some((record, score + contribution))
          case None                  => Some((m, contribution))
        }
      }
    }
    accum.values.toVector
      .map { case (m, base) => (m, base * boost(m, now)) }
      .sortBy { case (_, score) => -score }
      .map(_._1)
  }

  private def boost(m: ContextMemory, now: Timestamp): Double =
    if (recencyWeight == 0.0 && reinforcementWeight == 0.0) 1.0
    else 1.0 + recencyWeight * recencyFactor(m, now) + reinforcementWeight * reinforcementFactor(m)

  private[retrieval] def recencyFactor(m: ContextMemory, now: Timestamp): Double = {
    val freshest = math.max(m.created.value, m.modified.value)
    TemporalBoost.decay(math.max(0L, now.value - freshest).toDouble, recencyHalfLifeMs)
  }

  private[retrieval] def reinforcementFactor(m: ContextMemory): Double = {
    val dampened = math.log1p(math.max(0, m.accessCount).toDouble)
    dampened / (1.0 + dampened)
  }
}

object FuseStage {
  /** Conservative default recency scale — bounds the recency share of
    * the boost multiplier to 5%. */
  val DefaultRecencyWeight: Double = 0.05

  /** Conservative default reinforcement scale — bounds the
    * reinforcement share of the boost multiplier to 3%. */
  val DefaultReinforcementWeight: Double = 0.03

  /** Default recency half-life — one week ([[TemporalBoost.HalfLife]]
    * preset): a week-old memory's recency term is half a fresh one's. */
  val DefaultRecencyHalfLifeMs: Long = TemporalBoost.HalfLife.OneWeek
}
