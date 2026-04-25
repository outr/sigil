package sigil.vector

/**
 * A single similarity-search hit. `score` is backend-specific but
 * higher = more similar (cosine/dot product convention). `payload`
 * carries the same string map that was supplied at upsert time.
 */
case class VectorSearchResult(id: String,
                              score: Double,
                              payload: Map[String, String])
