package sigil.spatial

import fabric.rw.*
import lightdb.spatial.Point

/**
 * A named physical location. `point` is required — a Place always has a
 * coordinate. `address` and `name` are app-supplied (typically via a reverse-
 * geocoding service) and may be absent when only raw GPS is available.
 *
 * A Message with `location = Some(Place(point, None, None))` is "geotagged but
 * not enriched" — the framework's `Geocoder` can enrich it asynchronously when
 * a non-NoOp geocoder is wired.
 */
case class Place(point: Point,
                 address: Option[String] = None,
                 name: Option[String] = None) derives RW
