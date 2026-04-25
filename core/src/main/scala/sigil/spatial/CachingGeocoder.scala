package sigil.spatial

import lightdb.filter.*
import lightdb.spatial.Point
import lightdb.time.Timestamp
import lightdb.util.Nowish
import rapid.Task
import sigil.Sigil

import scala.concurrent.duration.*

/**
 * Wraps a [[Geocoder]] with a spatial-containment cache backed by
 * [[sigil.db.SigilDB.geocodingCache]]. Cache hits return the stored
 * [[GeocodingResult]] with zero delegate calls; cache misses invoke the
 * delegate and persist the result (boundary + place) for future hits.
 *
 * `ttl` controls when a stored entry is considered stale:
 *   - `Some(duration)` — entries older than `duration` are ignored and
 *     replaced on the next miss (on-read expiry; no background sweeper).
 *   - `None` — entries never expire.
 *
 * The default 30-day window is the typical trade-off between hit rate
 * and freshness (places occasionally rename or close). Tune per app.
 */
case class CachingGeocoder(delegate: Geocoder,
                           sigil: Sigil,
                           ttl: Option[FiniteDuration] = Some(30.days)) extends Geocoder {

  override def geocode(point: Point): Task[Option[GeocodingResult]] =
    lookupCached(point).flatMap {
      case hit @ Some(_) => Task.pure(hit)
      case None =>
        delegate.geocode(point).flatMap {
          case Some(result) => cacheResult(result).map(_ => Some(result))
          case None => Task.pure(None)
        }
    }

  private def lookupCached(point: Point): Task[Option[GeocodingResult]] =
    sigil.withDB(_.geocodingCache.transaction { tx =>
      tx.query.filter(m => m.boundary.spatialContains(point)).toList
    }).map { rows =>
      val now = System.currentTimeMillis()
      rows.find(row => isFresh(row.resolvedAt, now))
        .map(row => GeocodingResult(place = row.place, boundary = row.boundary))
    }

  private def isFresh(resolvedAt: Timestamp, nowMs: Long): Boolean = ttl match {
    case None => true
    case Some(limit) => (nowMs - resolvedAt.value) < limit.toMillis
  }

  private def cacheResult(result: GeocodingResult): Task[Unit] =
    sigil.withDB(_.geocodingCache.transaction { tx =>
      tx.insert(GeocodingCache(
        boundary = result.boundary,
        place = result.place,
        resolvedAt = Timestamp(Nowish())
      ))
    }).unit
}
