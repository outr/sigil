package sigil.workflow.tool

import fabric.{Json, str}
import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec, Effect, MutationTargeting, Resolution, TextToolOutput, Tool, ToolExample, ToolIO, ToolInput, ToolName, ToolProfile,
  ToolResult, ToolSpec
}
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
  val io: ToolIO[ApproveWorkflowInput, TextToolOutput] = ToolIO.derived[ApproveWorkflowInput, TextToolOutput].withExamples(
    ToolExample(
      "Approve a pending review",
      ApproveWorkflowInput(runId = "run-abc", stepId = "review")),
    ToolExample(
      "Approve with a reason note",
      ApproveWorkflowInput(runId = "run-abc", stepId = "review", comment = Some("looks correct after manual check")))
  )
  override val name = ToolName("approve_workflow")
  override val description =
    """Approve a workflow run paused on an approval step.
      |
      |`runId` is the run id; `stepId` is the id of the waiting approval step (visible
      |from the workflow's lifecycle Events). `comment` is optional free-form text —
      |passed through as the resume payload so the workflow's branching can match on it.""".stripMargin
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("workflow", "approve", "ok", "yes", "continue"))
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: ApproveWorkflowInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = withHostTyped(ctx) { host =>
    val workflowId = Id[Workflow](input.runId)
    val payload: Json = input.comment.filter(_.nonEmpty).fold[Json](str("approve"))(c => str(s"approve: $c"))
    host.withDB(_.workflows.transaction(_.get(workflowId))).flatMap {
      case None => Task.pure(ToolResult.failure(s"Workflow run '${input.runId}' not found."))
      case Some(wf) =>
        authorizeRun(host, wf, ctx.chain).flatMap {
          case Left(_) => Task.pure(ToolResult.failure(s"Workflow run '${input.runId}' not found."))
          case Right(_) =>
            host.workflowManager.resume(workflowId, Id[Step](input.stepId), payload)
              .map(_ => ToolResult.success(TextToolOutput(s"Workflow run '${input.runId}' approved at step '${input.stepId}'.")))
              .handleError(e => Task.pure(ToolResult.failure(s"Approve failed: ${e.getMessage}")))
        }
    }
  }
}
