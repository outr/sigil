package sigil.tool

import sigil.tool.model.ResponseContent

/**
 * One entry in the orchestrator's turn-scoped read cache: the settled
 * content of a read-only tool call plus the declarations invalidation
 * derives from. [[Freshness.Pure]] entries survive the whole turn;
 * [[Freshness.Stable]] entries are dropped when a mutating call lands
 * whose [[MutationTarget]] overlaps `target` (conservatively — any
 * mutation — when either side declares no target). Volatile reads are
 * never cached.
 */
final case class CachedToolRead(content: Vector[ResponseContent],
                                freshness: Freshness,
                                target: Option[MutationTarget])
