package sigil.spatial

import lightdb.spatial.Point
import rapid.Task

/**
 * Reverse-geocoding service abstraction. Takes a GPS point, returns a
 * [[GeocodingResult]] — an enriched [[Place]] paired with the polygon
 * `boundary` that encloses it. Used by `Sigil` to enrich user-authored
 * Messages asynchronously after `publish`.
 *
 * The paired `boundary` is what makes the [[CachingGeocoder]]
 * spatial-containment cache work: a GPS sample taken anywhere inside the
 * stored polygon turns into a cache hit without hitting the underlying
 * service. Geocoders that don't know a richer polygon return
 * `GeocodingResult(place, place.point)` — caching still works for that
 * exact coordinate, but nearby samples miss.
 *
 * Apps provide concrete implementations (typically backed by a Places-style
 * HTTP API and wrapped with [[CachingGeocoder]]).
 *
 * [[NoOpGeocoder]] is a first-class configuration — apps that want raw-GPS
 * tagging without external lookups keep it indefinitely.
 */
trait Geocoder {
  def geocode(point: Point): Task[Option[GeocodingResult]]
}

/**
 * Default [[Geocoder]] — returns `None` for every point. When wired, the
 * framework skips the entire enrichment step: no cache queries, no task
 * spawn, no log. Apps that want geotagged messages but no Place lookup
 * keep this indefinitely.
 */
object NoOpGeocoder extends Geocoder {
  override def geocode(point: Point): Task[Option[GeocodingResult]] = Task.pure(None)
}
