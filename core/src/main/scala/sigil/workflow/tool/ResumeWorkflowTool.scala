package sigil.workflow.tool

import fabric.{Json, Null, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}
import strider.Workflow
import strider.step.Step

case class ResumeWorkflowInput(runId: String,
                               stepId: String,
                               payload: Option[String] = None)
  extends ToolInput derives RW

/**
 * Resume a workflow run paused on a [[strider.step.Approval]] or
 * other waiting step. Used to satisfy human-in-the-loop pauses —
 * the user (or a tool acting as the user's proxy) supplies the
 * decision payload that lets the workflow continue.
 *
 * `payload` is the agent's chosen value (typically one of an
 * approval step's `options`). Empty payload resumes with
 * `Json.Null`.
 */
final class ResumeWorkflowTool extends Tool with WorkflowToolSupport {
  type Input = ResumeWorkflowInput
  type Output = TextToolOutput
  val inputRW = summon[RW[ResumeWorkflowInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("resume_workflow")
  val description =
    """Resume a workflow run paused on an approval / trigger step.
      |
      |`runId` is the run id; `stepId` is the id of the waiting step (visible from
      |the workflow's lifecycle Events).
      |`payload` (optional) is the chosen value — for approval steps, one of the
      |configured options.""".stripMargin
  override val examples = List(
    ToolExample(
      "approve a pending approval",
      ResumeWorkflowInput(runId = "run-abc", stepId = "review", payload = Some("approve"))
    )
  )
  override val keywords = Set("workflow", "resume", "approve", "continue")

  override def executeResult(input: ResumeWorkflowInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = withHostResult(ctx) { host =>
    val workflowId = Id[Workflow](input.runId)
    host.withDB(_.workflows.transaction(_.get(workflowId))).flatMap {
      case None => Task.pure(s"Workflow run '${input.runId}' not found.")
      case Some(wf) =>
        authorizeRun(host, wf, ctx.chain).flatMap {
          case Left(_) => Task.pure(s"Workflow run '${input.runId}' not found.")
          case Right(_) =>
            val payloadJson: Json = input.payload.filter(_.nonEmpty).fold[Json](Null)(str)
            val payloadDisplay = input.payload.getOrElse("")
            host.workflowManager.resume(workflowId, Id[Step](input.stepId), payloadJson)
              .map(_ => s"Workflow run '${input.runId}' resumed at step '${input.stepId}' with payload '$payloadDisplay'.")
              .handleError(e => Task.pure(s"Resume failed: ${e.getMessage}"))
        }
    }
  }
}
