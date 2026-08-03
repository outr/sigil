package sigil.tool

import sigil.tool.model.ResponseContent

/**
 * One entry in the orchestrator's turn-scoped read cache: the settled
 * content of a read-only tool call plus the [[Freshness]] invalidation
 * derives from. [[Freshness.Pure]] entries survive the whole turn;
 * [[Freshness.Stable]] entries are dropped as soon as ANY mutating call
 * lands in the turn. Volatile reads are never cached.
 */
final case class CachedToolRead(content: Vector[ResponseContent],
                                freshness: Freshness)
