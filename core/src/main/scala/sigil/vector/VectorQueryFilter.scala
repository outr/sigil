package sigil.vector

/**
 * Payload predicate for [[VectorIndex.search]]. `exact` clauses match a
 * single value per key; `anyOf` clauses match when the payload value is
 * any member of the supplied set — the shape space-scoped searches need
 * so the scope applies inside the index's top-K cut instead of after
 * it. All clauses AND together; an empty filter matches every point.
 */
case class VectorQueryFilter(exact: Map[String, String] = Map.empty,
                             anyOf: Map[String, Set[String]] = Map.empty) {
  def isEmpty: Boolean = exact.isEmpty && anyOf.isEmpty

  /** In-memory evaluation of the predicate against a point's payload. */
  def matches(payload: Map[String, String]): Boolean =
    exact.forall { case (k, v) => payload.get(k).contains(v) } &&
      anyOf.forall { case (k, vs) => payload.get(k).exists(vs.contains) }
}
