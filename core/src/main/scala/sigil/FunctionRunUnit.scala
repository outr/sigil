package sigil

import lightdb.id.Id
import rapid.Task
import sigil.conversation.Conversation

/**
 * Private adapter that turns a `Task[T]` body plus loose metadata
 * into a [[RunUnit]] without forcing every callsite to declare a
 * named class. Used by [[Sigil.runAsFrameworkWorkflow]] to preserve
 * its pre-existing public signature while delegating to
 * [[RunUnit.execute]].
 *
 * `cleanup` runs on both success and failure — deregistration of
 * the active-framework-workflows map MUST happen even when the body
 * crashed, or `cancel_framework_workflow` would still see a phantom
 * entry.
 *
 * `cancellationToken` is retained as metadata for diagnostics. The
 * cancellation flow itself rides on the `step` callback / `token`
 * surface inside the wrap body — when the body raises
 * [[CancellationException]], [[RunUnit.execute]]'s default failure
 * rendering already includes both "cancelled" and the requested
 * reason via the exception's `getMessage` (defined on
 * [[CancellationException]] as `"workflow '<id>' cancelled: <reason>"`).
 */
private[sigil] final class FunctionRunUnit[T](
  override val label: String,
  override val workflowType: String,
  override val conversationId: Option[Id[Conversation]],
  override val run: Task[T],
  cleanup: Task[Unit],
  cancellationToken: Option[CancellationToken] = None,
  preassignedWorkflowId: Option[String] = None
) extends RunUnit[T] {

  override lazy val workflowId: String =
    preassignedWorkflowId.orElse(cancellationToken.map(_.workflowId))
      .getOrElse(RunUnit.freshWorkflowId())

  override def onCompleted(result: T): Task[Unit] = cleanup

  override def onFailed(t: Throwable): Task[Unit] = cleanup
}
