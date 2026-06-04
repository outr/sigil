package sigil.workflow.tool

import fabric.{Json, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}
import strider.Workflow
import strider.step.Step

case class ApproveWorkflowInput(runId: String,
                                stepId: String,
                                comment: Option[String] = None)
  extends ToolInput derives RW

/**
 * Approve a workflow run paused on an [[strider.step.Approval]]
 * step. Sugar over [[ResumeWorkflowTool]] with the canonical
 * `"approve"` payload (or, when `comment` is provided, an
 * `"approve: <comment>"` string the workflow's branching expression
 * can match on for distinct approval reasons). Bug #51.
 *
 * Distinct from `cancel_framework_workflow` — that's for in-flight
 * framework operations (pre-flight, compress, …). This is for
 * agent / user decisions on application-level Strider workflows
 * that paused at an approval gate.
 *
 * Idempotent against an already-approved or already-declined run
 * — Strider's `resume` returns an error which surfaces in the
 * tool's reply text.
 */
final class ApproveWorkflowTool extends Tool with WorkflowToolSupport {
  type Input = ApproveWorkflowInput
  type Output = TextToolOutput
  val inputRW = summon[RW[ApproveWorkflowInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("approve_workflow")
  val description =
    """Approve a workflow run paused on an approval step.
      |
      |`runId` is the run id; `stepId` is the id of the waiting approval step (visible
      |from the workflow's lifecycle Events). `comment` is optional free-form text —
      |passed through as the resume payload so the workflow's branching can match on it.""".stripMargin
  override val examples = List(
    ToolExample(
      "Approve a pending review",
      ApproveWorkflowInput(runId = "run-abc", stepId = "review")),
    ToolExample(
      "Approve with a reason note",
      ApproveWorkflowInput(runId = "run-abc", stepId = "review", comment = Some("looks correct after manual check")))
  )
  override val keywords = Set("workflow", "approve", "ok", "yes", "continue")

  override def executeResult(input: ApproveWorkflowInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
    withHostResult(ctx) { host =>
      val workflowId = Id[Workflow](input.runId)
      val payload: Json = input.comment.filter(_.nonEmpty).fold[Json](str("approve"))(c => str(s"approve: $c"))
      host.withDB(_.workflows.transaction(_.get(workflowId))).flatMap {
        case None => Task.pure(s"Workflow run '${input.runId}' not found.")
        case Some(wf) =>
          authorizeRun(host, wf, ctx.chain).flatMap {
            case Left(_) => Task.pure(s"Workflow run '${input.runId}' not found.")
            case Right(_) =>
              host.workflowManager.resume(workflowId, Id[Step](input.stepId), payload)
                .map(_ => s"Workflow run '${input.runId}' approved at step '${input.stepId}'.")
                .handleError(e => Task.pure(s"Approve failed: ${e.getMessage}"))
          }
      }
    }
}
