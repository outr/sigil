package sigil.spatial

import fabric.rw.*
import lightdb.doc.{JsonConversion, RecordDocument, RecordDocumentModel}
import lightdb.id.Id
import lightdb.spatial.Geo
import lightdb.time.Timestamp
import rapid.Unique

/**
 * Spatial-containment cache entry for reverse-geocoding results.
 * `boundary` is the polygon (or point fallback) that encloses the
 * cached [[Place]]; `spatialContains(point)` on the indexed field
 * turns point-to-Place lookups into a single spatial index hit and
 * avoids re-calling the underlying geocoder for GPS samples within the
 * same physical boundary (standing at a coffee shop, walking within a
 * park, etc.).
 *
 * `resolvedAt` is the wall-clock time the entry was obtained. Expiry is
 * enforced on read by [[CachingGeocoder]] — stale rows are ignored and
 * replaced on the next cache miss; no background sweeper runs.
 */
case class GeocodingCache(boundary: Geo,
                          place: Place,
                          resolvedAt: Timestamp = Timestamp(),
                          created: Timestamp = Timestamp(),
                          modified: Timestamp = Timestamp(),
                          _id: Id[GeocodingCache] = GeocodingCache.id())
  extends RecordDocument[GeocodingCache]

object GeocodingCache extends RecordDocumentModel[GeocodingCache] with JsonConversion[GeocodingCache] {
  implicit override def rw: RW[GeocodingCache] = RW.gen

  val boundary: I[Geo] = field.index(_.boundary)

  override def id(value: String = Unique()): Id[GeocodingCache] = Id(value)
}
