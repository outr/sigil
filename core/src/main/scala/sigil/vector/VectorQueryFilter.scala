package sigil.vector

/**
 * Payload predicate for [[VectorIndex.search]]. `exact` clauses match a
 * single value per key; `anyOf` clauses match when the payload value is
 * any member of the supplied set — the shape space-scoped searches need
 * so the scope applies inside the index's top-K cut instead of after
 * it. All clauses AND together; an empty filter matches every point.
 *
 * An `anyOf` clause with an EMPTY value set matches nothing — "the
 * value must be one of {}" is unsatisfiable, and every evaluation
 * surface ([[matches]], the default per-value expansion in
 * [[VectorIndex.search]], and [[QdrantOps]]' native translation) agrees
 * on that reading. Callers that mean "no constraint" omit the key
 * rather than passing an empty set.
 */
case class VectorQueryFilter(exact: Map[String, String] = Map.empty,
                             anyOf: Map[String, Set[String]] = Map.empty) {
  def isEmpty: Boolean = exact.isEmpty && anyOf.isEmpty

  /**
   * `true` when some `anyOf` clause has an empty value set — the
   * filter is unsatisfiable and backends can short-circuit to no
   * results without a round-trip.
   */
  def matchesNothing: Boolean = anyOf.valuesIterator.exists(_.isEmpty)

  /**
   * In-memory evaluation of the predicate against a point's payload.
   */
  def matches(payload: Map[String, String]): Boolean =
    exact.forall { case (k, v) => payload.get(k).contains(v) } &&
      anyOf.forall { case (k, vs) => payload.get(k).exists(vs.contains) }
}
