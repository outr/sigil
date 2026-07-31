package sigil.tool.core

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, Resolution, Tool, ToolExample, ToolIO, ToolInput, ToolName, ToolOutput, ToolProfile, ToolSpec}

case class CancelFrameworkWorkflowInput(workflowId: String,
                                        reason: Option[String] = None) extends ToolInput derives RW

enum CancelFrameworkWorkflowOutput extends ToolOutput derives RW {

  /** Cancellation flag flipped successfully — the workflow body
    * will honour it at its next checkpoint and emit a
    * `FrameworkWorkflowPhase.Failed("cancelled: …", …)` Notice. */
  case Cancelled(workflowId: String, workflowType: String, label: String)

  /** Workflow id wasn't found in the active set — either the
    * workflow already finished, or the id is wrong. Idempotent
    * shape so re-cancellation is a clean no-op. */
  case NotActive(workflowId: String)

  /** Workflow was already cancelled by an earlier call. */
  case AlreadyCancelled(workflowId: String, existingReason: String)
}

/**
 * Cancel an in-flight framework workflow (pre-flight, compress,
 * by id.
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
case object CancelFrameworkWorkflowTool extends Tool {
  type Input  = CancelFrameworkWorkflowInput
  type Output = CancelFrameworkWorkflowOutput
  val io: ToolIO[CancelFrameworkWorkflowInput, CancelFrameworkWorkflowOutput] = ToolIO.derived[CancelFrameworkWorkflowInput, CancelFrameworkWorkflowOutput].withExamples(
    ToolExample("Cancel a slow compress",
      CancelFrameworkWorkflowInput(workflowId = "wf-abc-123", reason = Some("user clicked cancel")))
  )
  override val name = ToolName("cancel_framework_workflow")
  override val description =
    """Cancel a framework-internal workflow (pre-flight, compress, frame-load, …) by its
      |workflow id. Cooperative: the workflow body honours the cancellation at its next
      |internal checkpoint, so very short operations may complete before the cancel takes
      |effect. Idempotent.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("cancel", "framework", "workflow", "abort", "stop", "preflight", "compress"))
  )


  protected def resolve: Resolution[Input, Output] = Resolution.Simple(executeOutput)

  private def executeOutput(input: CancelFrameworkWorkflowInput,
                            ctx: ToolContext): Task[CancelFrameworkWorkflowOutput] = Task {
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
