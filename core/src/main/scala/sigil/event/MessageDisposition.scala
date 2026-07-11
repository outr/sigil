package sigil.event

import fabric.rw.*

/**
 * How a [[Message]] resolves — was the agent's reply a normal
 * answer/status (`Success`) or did it signal that the requested work
 * couldn't be completed (`Failure`)?
 *
 * Lives on the `Message` itself rather than as a content-block
 * variant because it's a Message-level property: the orchestrator's
 * refusal-challenge intercept, the bug-#6 failure-surface path, and
 * downstream "show me failed turns" queries all walk Messages and
 * key off this directly. Content is the *reason*; disposition is the
 * *verdict*.
 *
 * `Failure` carries optional metadata the framework populates when
 * it caught an exception at the tool boundary — `recoverable` for
 * retry semantics, `errorContext` for the typed exception
 * details. Agent-emitted failures (via `respond` with
 * `disposition = Failure`) default to `recoverable = false,
 * errorContext = None`; the agent has decided it can't proceed.
 */
enum MessageDisposition derives RW {

  /**
   * Normal reply — the agent answered, asked, or reported status.
   */
  case Success

  /**
   * The agent could not complete the requested work. Content
   * carries the reason as markdown. Framework-emitted failures
   * (caught tool exceptions, etc.) populate `recoverable` and
   * `errorContext`; agent-emitted failures leave the defaults.
   */
  case Failure(recoverable: Boolean = false,
               errorContext: Option[ErrorContext] = None)
}
