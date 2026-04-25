package sigil.spatial

import lightdb.spatial.Point
import rapid.Task

import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic in-memory [[Geocoder]] for tests. Returns seeded
 * [[GeocodingResult]]s from a lookup function; records invocation count
 * so specs can assert the wrapping [[CachingGeocoder]] short-circuits on
 * cache hits.
 */
case class InMemoryGeocoder(lookup: Point => Option[GeocodingResult]) extends Geocoder {
  private val counter: AtomicInteger = new AtomicInteger(0)

  override def geocode(point: Point): Task[Option[GeocodingResult]] = Task {
    counter.incrementAndGet()
    lookup(point)
  }

  /** Number of times `geocode` has been invoked on this instance. */
  def invocationCount: Int = counter.get()
}

object InMemoryGeocoder {
  def fromMap(results: Map[Point, GeocodingResult]): InMemoryGeocoder =
    InMemoryGeocoder(results.get)

  def single(point: Point, result: GeocodingResult): InMemoryGeocoder =
    fromMap(Map(point -> result))
}
