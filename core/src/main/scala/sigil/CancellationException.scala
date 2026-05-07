package sigil

/**
 * Raised by [[CancellationToken.checkpoint]] / [[CancellationToken.guard]]
 * when a framework workflow's body is poll-checking cancellation
 * and finds the flag set.
 *
 * The framework's `runAsFrameworkWorkflow` translates this into a
 * `FrameworkWorkflowPhase.Failed(reason = "cancelled: $reason", …)`
 * Notice. Bug #51.
 */
final class CancellationException(val workflowId: String, val reason: String)
  extends RuntimeException(s"workflow '$workflowId' cancelled: $reason")
