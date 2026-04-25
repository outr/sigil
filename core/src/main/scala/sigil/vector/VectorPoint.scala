package sigil.vector

/**
 * A single indexable point — a dense vector plus a payload used for
 * filtering and result hydration. `id` is the caller-chosen key (string
 * so it can round-trip through any backend); Sigil typically uses the
 * UUID of the source record.
 */
case class VectorPoint(id: String,
                       vector: Vector[Double],
                       payload: Map[String, String])
