package sigil.signal

import fabric.rw.*

/**
 * Lifecycle phase for a [[FrameworkWorkflowNotice]]. Bug #50.
 *
 * Phases mirror the application-workflow lifecycle (Started →
 * Step → Completed | Failed) so client UIs can render the same
 * progress shape regardless of whether the operation is a
 * Strider-driven app workflow or a framework-internal pulse.
 */
enum FrameworkWorkflowPhase derives RW {

  /** First emit. The operation has begun. `label` describes the
    * operation in user-facing terms ("rendering pre-flight",
    * "compressing context"). */
  case Started(label: String)

  /** Optional intermediate emit. Used by multi-step framework
    * operations (compression: estimate → invoke → swap-frames).
    * `durationMs` is from the workflow's start (not the prior
    * step). */
  case Step(label: String, durationMs: Long)

  /** Terminal — success. `durationMs` is total wall time from
    * Started to here. */
  case Completed(durationMs: Long)

  /** Terminal — failure. `reason` carries the exception's class
    * name + message; full stack lives in scribe logs (don't
    * widen this for diagnostics — Notices are user-facing). */
  case Failed(reason: String, durationMs: Long)
}
