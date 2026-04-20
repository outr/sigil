package sigil

import fabric.rw.*

/**
 * A typed wrapper around a short class name of a subtype registered in a
 * [[PolyType]]. Constructed only via each PolyType's `name` namespace
 * (see [[PolyType.name]]) — apps never build one directly, so every
 * `PolyName[T]` in hand was derived from (or validated against) T's live
 * registration.
 *
 * Acts as a reusable primitive wherever code needs to identify "which
 * subtype of T" without carrying a full instance and without resorting
 * to raw strings.
 */
final case class PolyName[T] private[sigil] (name: String) derives RW
