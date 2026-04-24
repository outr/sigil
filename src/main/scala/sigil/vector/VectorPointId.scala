package sigil.vector

/**
 * Derive a deterministic UUID for a lightdb id. Qdrant requires
 * point ids to be UUIDs or unsigned ints; lightdb ids are arbitrary
 * strings. Using a name-based UUID (v3/v5-style via
 * `UUID.nameUUIDFromBytes`) gives a stable point id that upsert can
 * replace deterministically.
 */
object VectorPointId {
  def apply(lightdbId: String): String =
    java.util.UUID.nameUUIDFromBytes(lightdbId.getBytes("UTF-8")).toString
}
