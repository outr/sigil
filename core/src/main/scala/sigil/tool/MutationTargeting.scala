package sigil.tool

import scala.reflect.ClassTag

/**
 * How a mutating tool names what it mutates. Declared on
 * [[Effect.Mutating]] / [[Effect.Destructive]]:
 *
 *   - [[MutationTargeting.typed]] — a typed extractor over the tool's
 *     own input class. Class-checked at dispatch: a foreign input
 *     reads as no target, never a cast error.
 *   - [[MutationTargeting.none]] — the target is unknowable from the
 *     input (`bash`, `respond`). Cache invalidation and churn
 *     detection treat it conservatively.
 */
sealed trait MutationTargeting {

  /**
   * Best-effort target extraction from an untyped input. Total —
   * a foreign input class yields `None`.
   */
  def targetOf(input: ToolInput): Option[MutationTarget]
}

object MutationTargeting {

  /**
   * Explicit "targets unknown" — conservative cache / churn behavior.
   */
  val none: MutationTargeting = new MutationTargeting {
    override def targetOf(input: ToolInput): Option[MutationTarget] = None
  }

  /**
   * Typed extractor: `f` runs only when `input` is an `I`; any other
   * input class yields `None`.
   */
  def typed[I <: ToolInput: ClassTag](f: I => Option[MutationTarget]): MutationTargeting = new MutationTargeting {
    override def targetOf(input: ToolInput): Option[MutationTarget] = input match {
      case i: I => f(i)
      case _ => None
    }
  }

  /**
   * Common case — the target is a single path-like input field.
   */
  def path[I <: ToolInput: ClassTag](f: I => String): MutationTargeting =
    typed[I](i => Some(MutationTarget(f(i))))
}
