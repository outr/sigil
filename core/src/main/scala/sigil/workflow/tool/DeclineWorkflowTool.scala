package sigil.workflow.tool

import fabric.{Json, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}
import strider.Workflow
import strider.step.Step

case class DeclineWorkflowInput(runId: String,
                                stepId: String,
                                reason: Option[String] = None) extends ToolInput derives RW

/**
 * Decline a workflow run paused on an [[strider.step.Approval]]
 * step. Sugar over [[ResumeWorkflowTool]] with the canonical
 * `"decline"` payload (or `"decline: <reason>"` when reason is
 * provided). Bug #51.
 *
 * The workflow's declined-branch path runs after this resolves —
 * each approval step's authoring decides what that path does
 * (rollback, compensation, alternate path, immediate failure).
 * Idempotent against an already-resumed run.
 */
final class DeclineWorkflowTool extends Tool with WorkflowToolSupport {
  type Input  = DeclineWorkflowInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[DeclineWorkflowInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("decline_workflow")
  val description =
    """Decline a workflow run paused on an approval step.
      |
      |`runId` is the run id; `stepId` is the id of the waiting approval step. `reason` is
      |optional free-form text — appended to the resume payload so the workflow's
      |branching can match on it.""".stripMargin
  override val examples = List(
    ToolExample("Decline a deploy approval",
      DeclineWorkflowInput(runId = "run-abc", stepId = "deploy-gate")),
    ToolExample("Decline with a reason",
      DeclineWorkflowInput(runId = "run-abc", stepId = "deploy-gate", reason = Some("staging tests failing")))
  )
  override val keywords = Set("workflow", "decline", "reject", "no", "deny", "refuse")

  override def executeResult(input: DeclineWorkflowInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = withHostResult(ctx) { host =>
    val workflowId = Id[Workflow](input.runId)
    val payload: Json = input.reason.filter(_.nonEmpty).fold[Json](str("decline"))(r => str(s"decline: $r"))
    host.withDB(_.workflows.transaction(_.get(workflowId))).flatMap {
      case None => Task.pure(s"Workflow run '${input.runId}' not found.")
      case Some(wf) =>
        authorizeRun(host, wf, ctx.chain).flatMap {
          case Left(_) => Task.pure(s"Workflow run '${input.runId}' not found.")
          case Right(_) =>
            host.workflowManager.resume(workflowId, Id[Step](input.stepId), payload)
              .map(_ => s"Workflow run '${input.runId}' declined at step '${input.stepId}'.")
              .handleError(e => Task.pure(s"Decline failed: ${e.getMessage}"))
        }
    }
  }
}
