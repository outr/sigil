package sigil.tool

import lightdb.id.Id
import sigil.event.Event

/**
 * How a tool dispatch reaches [[ToolExecutor]]'s gates:
 *
 *   - [[Gated]] — the full gate pipeline runs: consent (when the
 *     tool declares a consent gate and the caller isn't in an
 *     autonomous posture), then preconditions. The orchestrator's
 *     wire path and workflow step dispatch use this.
 *   - [[PreGated]] — consent was already answered for the parent
 *     dispatch identified by `by` (composition: one tool's body
 *     invoking another). Skipping consent is a recorded decision,
 *     not an accident of calling an inner method; preconditions
 *     still run.
 */
enum GateContext {
  case Gated
  case PreGated(by: Id[Event])
}
