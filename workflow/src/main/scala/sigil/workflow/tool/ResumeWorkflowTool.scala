package sigil.workflow.tool

import fabric.{Json, Null, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import strider.Workflow
import strider.step.Step

case class ResumeWorkflowInput(runId: String,
                               stepId: String,
                               payload: String = "") extends ToolInput derives RW

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
final class ResumeWorkflowTool extends TypedTool[ResumeWorkflowInput](
  name = ToolName("resume_workflow"),
  description =
    """Resume a workflow run paused on an approval / trigger step.
      |
      |`runId` is the run id; `stepId` is the id of the waiting step (visible from
      |the workflow's lifecycle Events or `list_workflows`).
      |`payload` (optional) is the chosen value — for approval steps, one of the
      |configured options.""".stripMargin,
  examples = List(
    ToolExample(
      "approve a pending approval",
      ResumeWorkflowInput(runId = "run-abc", stepId = "review", payload = "approve")
    )
  ),
  keywords = Set("workflow", "resume", "approve", "continue")
) with WorkflowToolSupport {
  override protected def executeTyped(input: ResumeWorkflowInput, ctx: TurnContext): Stream[Event] = {
    workflowHost(ctx) match {
      case Left(err) => reply(ctx, err, isError = true)
      case Right(host) =>
        val workflowId = Id[Workflow](input.runId)
        val task = host.workflowDb.workflows.transaction(_.get(workflowId)).flatMap {
          case None => Task.pure(s"Workflow run '${input.runId}' not found.")
          case Some(wf) =>
            authorizeRun(host, wf, ctx.chain).flatMap {
              case Left(_) => Task.pure(s"Workflow run '${input.runId}' not found.")
              case Right(_) =>
                val payloadJson: Json = if (input.payload.isEmpty) Null else str(input.payload)
                host.workflowManager.resume(workflowId, Id[Step](input.stepId), payloadJson)
                  .map(_ => s"Workflow run '${input.runId}' resumed at step '${input.stepId}' with payload '${input.payload}'.")
                  .handleError(e => Task.pure(s"Resume failed: ${e.getMessage}"))
            }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
