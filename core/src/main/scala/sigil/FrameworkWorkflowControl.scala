package sigil

import rapid.Task

/**
 * Control surface handed to a framework-workflow body. Bug #51.
 *
 * Bundles the [[step]] callback (emits a
 * [[sigil.signal.FrameworkWorkflowPhase.Step]] Notice) with the
 * [[token]] (cooperative cancellation primitive). Bodies poll the
 * token at decision boundaries; calls to `step` already include an
 * implicit checkpoint, so a body that emits Steps regularly will
 * honour cancellation between them without explicit `token.checkpoint`
 * calls.
 *
 * Apps composing their own framework workflows accept this as the
 * single argument of their lifted Task body — keeps the API
 * forward-compatible if more control surfaces are added later.
 */
final case class FrameworkWorkflowControl(step: String => Task[Unit], token: CancellationToken)
