package sigil.vector

/**
 * Re-ranks a list of [[VectorSearchResult]]s by proximity of each
 * candidate's stored `timestamp` payload to a query reference time.
 *
 * The boost is exponential decay: candidates whose `timestamp` is
 * exactly the reference time get a multiplier of 1.0; candidates
 * whose distance equals `halfLifeMs` get 0.5; and so on. Blended
 * into the existing score as:
 *
 *   `finalScore = baseScore * (1.0 - temporalWeight) + boost * temporalWeight`
 *
 * Candidates without a `timestamp` payload are left untouched (their
 * temporal boost is zero — so they only participate via the base
 * score's share of the blend).
 *
 * Convention: payloads store the timestamp as a numeric string under
 * [[TemporalBoost.TimestampKey]] (default `"timestamp"`). This
 * matches how the benchmark runners store it. Apps using their own
 * payload key supply an override.
 */
case class TemporalBoost(halfLifeMs: Long,
                         temporalWeight: Double = 0.3,
                         timestampKey: String = TemporalBoost.TimestampKey) {

  require(halfLifeMs > 0, "halfLifeMs must be positive")
  require(temporalWeight >= 0.0 && temporalWeight <= 1.0, "temporalWeight must be in [0, 1]")

  /** Apply the temporal re-rank to `candidates` using `referenceTimeMs`
    * as the query's notional timestamp. Stable order among tied
    * scores (sortBy is stable). */
  def rerank(candidates: List[VectorSearchResult], referenceTimeMs: Long): List[VectorSearchResult] = {
    if (candidates.isEmpty) Nil
    else candidates.map { c =>
      val boost = c.payload.get(timestampKey).flatMap(_.toLongOption) match {
        case Some(ts) =>
          val delta = math.abs(ts - referenceTimeMs).toDouble
          math.pow(0.5, delta / halfLifeMs.toDouble)
        case None => 0.0
      }
      val blended = (1.0 - temporalWeight) * c.score + temporalWeight * boost
      c.copy(score = blended)
    }.sortBy(-_.score)
  }
}

object TemporalBoost {

  /** Conventional payload key under which timestamps (millis since
    * epoch, as a numeric string) are stored for temporal reranking. */
  val TimestampKey: String = "timestamp"

  /** Preset half-lives covering common retrieval horizons. */
  object HalfLife {
    val OneHour: Long = 60L * 60L * 1000L
    val OneDay: Long = 24L * OneHour
    val OneWeek: Long = 7L * OneDay
    val OneMonth: Long = 30L * OneDay
    val OneYear: Long = 365L * OneDay
  }
}
