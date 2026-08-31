package sigil.event

import fabric.rw.*

/**
 * Why the framework declined to dispatch a tool call the model made.
 *
 * A refused call still owns a durable [[ToolInvoke]] — the model made
 * it, the wire must pair it, and the agent reads the corrective the
 * refusal emitted. But no tool ran, so the invoke's [[ToolOutcome]]
 * stays `Pending`: a `Success` there would read to every consumer of
 * the durable row as a tool that ran and changed state.
 *
 * That leaves `Pending` covering two unrelated situations — a result
 * that raced past the frame, and a dispatch that never happened. They
 * need opposite treatment: a raced call is worth retrying (the result
 * exists, the agent just hasn't seen it), a refused one never is. This
 * marker is what tells them apart, so the duplicate-call cap and the
 * raced-reissue redirect each count only their own.
 */
enum DispatchRefusal derives RW {

  /**
   * The duplicate-call cap: this exact (tool, canonical args) call has
   * already run in this turn and repeating it cannot produce a
   * different answer.
   */
  case DuplicateCap

  /**
   * The per-response cap: the model fired more action tools in one
   * completion than the framework dispatches. The specific call is not
   * convicted — re-issuing it alone on a later iteration is exactly
   * what the corrective asks for.
   */
  case PerResponseCap

  /**
   * The raced-reissue bound: the same call has been re-issued while its
   * result kept arriving after the prompt was built, and the redirect
   * hands the agent that result instead of running it again.
   */
  case RacedReissue
}
