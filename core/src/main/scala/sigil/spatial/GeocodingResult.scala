package sigil.spatial

import lightdb.spatial.Geo

/**
 * Outcome of a successful reverse-geocode: the enriched [[Place]] (name,
 * address, center point) plus the `boundary` polygon that encloses it.
 *
 * Geocoders that know the enclosing polygon (e.g. Places-API viewports)
 * populate `boundary` with it — the spatial-containment cache then turns
 * any subsequent point inside that polygon into a hit without calling the
 * underlying service. Geocoders that only know the center point pass
 * `place.point` as the boundary; caching still works for that exact point
 * but misses for nearby samples.
 */
case class GeocodingResult(place: Place, boundary: Geo)
