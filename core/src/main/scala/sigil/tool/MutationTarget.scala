package sigil.tool

/**
 * The external target a mutating call touches, when the input names
 * one — for file tools, the path. Compared by value: the progress
 * checkpoint's churn detection and the read cache's overlap
 * invalidation both treat equal values as the same target.
 */
final case class MutationTarget(value: String)
