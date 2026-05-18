package sigil

import rapid.Task

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cooperative cancellation primitive handed to the body of a
 * framework workflow ([[Sigil.runAsFrameworkWorkflow]]). Bug #51.
 *
 * A workflow body checks its token at decision boundaries (between
 * Steps, before issuing a long-running call). When cancellation has
 * been requested via `cancel_framework_workflow`, the token's
 * checkpoint methods raise [[CancellationException]] which the
 * outer wrapper translates into a `FrameworkWorkflowPhase.Failed`
 * Notice with reason `"cancelled by <reason>"`.
 *
 * Cooperative — the body has to poll. The framework can't unwind
 * an in-flight HTTP stream synchronously without provider-specific
 * cancellation handles (which spice's `HttpClient` doesn't
 * surface), so the design hinges on giving the body explicit
 * checkpoints. Callers that don't poll get standard non-cancelable
 * behaviour; callers that poll honor cancellation at the next
 * checkpoint.
 */
final class CancellationToken(val workflowId: String) {
  private val flag: AtomicBoolean = new AtomicBoolean(false)
  @volatile private var cancellationReason: String = ""

  /**
   * Has cancellation been requested? Snapshot read; safe to call
   * from any thread.
   */
  def isCancelled: Boolean = flag.get()

  /**
   * The reason cancellation was requested ("user", "agent",
   * tool-call attribution string), or empty if not cancelled.
   */
  def reason: String = cancellationReason

  /**
   * Mark the workflow as cancelled. Idempotent — first reason
   * wins. Returns `true` if this call flipped the flag, `false`
   * if it was already cancelled.
   */
  def cancel(reason: String): Boolean = {
    val flipped = flag.compareAndSet(false, true)
    if (flipped) cancellationReason = reason
    flipped
  }

  /**
   * Raise [[CancellationException]] right now if cancellation has
   * been requested. Use at decision boundaries. Returns `Task.unit`
   * when not cancelled, so the caller can chain in a
   * for-comprehension cleanly.
   */
  def checkpoint: Task[Unit] =
    if (isCancelled) Task.error(new CancellationException(workflowId, cancellationReason))
    else Task.unit

  /**
   * Wrap a Task so it raises `CancellationException` BEFORE the
   * inner task runs if cancellation has been requested. The inner
   * task itself isn't interrupted mid-execution — that requires
   * cooperation from whatever it does (HTTP client cancellation
   * handles, etc.).
   */
  def guard[A](task: Task[A]): Task[A] = checkpoint.flatMap(_ => task)
}
