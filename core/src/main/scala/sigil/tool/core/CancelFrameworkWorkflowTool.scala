package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedOutputTool}

case class CancelFrameworkWorkflowInput(workflowId: String,
                                        reason: Option[String] = None)
  extends ToolInput derives RW

enum CancelFrameworkWorkflowOutput derives RW {

  /**
   * Cancellation flag flipped successfully — the workflow body
   * will honour it at its next checkpoint and emit a
   * `FrameworkWorkflowPhase.Failed("cancelled: …", …)` Notice.
   */
  case Cancelled(workflowId: String, workflowType: String, label: String)

  /**
   * Workflow id wasn't found in the active set — either the
   * workflow already finished, or the id is wrong. Idempotent
   * shape so re-cancellation is a clean no-op.
   */
  case NotActive(workflowId: String)

  /**
   * Workflow was already cancelled by an earlier call.
   */
  case AlreadyCancelled(workflowId: String, existingReason: String)
}

/**
 * Cancel an in-flight framework workflow (pre-flight, compress,
 * frame-load, …) by id. Bug #51.
 *
 * Cooperative — the workflow body has to reach a checkpoint
 * (between Steps, before issuing a long-running call) before the
 * cancellation actually takes effect. Short workflows may run to
 * completion before the next checkpoint; that's fine — the call
 * is idempotent.
 *
 * `cancel_workflow` is the analogous tool for application-level
 * Strider workflows (different runtime, different lifecycle). They
 * coexist; the agent picks based on which kind of run it's
 * cancelling. The `find_capability` keyword set distinguishes them
 * ("framework workflow" vs "workflow run / strider").
 */
case object CancelFrameworkWorkflowTool
  extends TypedOutputTool[CancelFrameworkWorkflowInput, CancelFrameworkWorkflowOutput](
    name = ToolName("cancel_framework_workflow"),
    description =
      """Cancel a framework-internal workflow (pre-flight, compress, frame-load, …) by its
      |workflow id. Cooperative: the workflow body honours the cancellation at its next
      |internal checkpoint, so very short operations may complete before the cancel takes
      |effect. Idempotent.""".stripMargin,
    examples = List(
      ToolExample(
        "Cancel a slow compress",
        CancelFrameworkWorkflowInput(workflowId = "wf-abc-123", reason = Some("user clicked cancel")))
    ),
    keywords = Set("cancel", "framework", "workflow", "abort", "stop", "preflight", "compress")
  ) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: CancelFrameworkWorkflowInput,
                                      ctx: TurnContext): Task[CancelFrameworkWorkflowOutput] = Task {
    val sigil = ctx.sigil
    val reason = input.reason.getOrElse(s"agent ${ctx.caller}")
    sigil.activeFrameworkWorkflows.find(_.workflowId == input.workflowId) match {
      case None =>
        CancelFrameworkWorkflowOutput.NotActive(input.workflowId)
      case Some(active) if active.cancellationToken.isCancelled =>
        CancelFrameworkWorkflowOutput.AlreadyCancelled(input.workflowId, active.cancellationToken.reason)
      case Some(active) =>
        sigil.cancelFrameworkWorkflow(input.workflowId, reason)
        CancelFrameworkWorkflowOutput.Cancelled(active.workflowId, active.workflowType, active.label)
    }
  }
}
