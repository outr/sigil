package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, ToolResult}
import sigil.workflow.{WorkflowSigil, WorkflowTemplate}

import scala.concurrent.duration.*

case class GetWorkflowInput(workflowId: String) extends ToolInput derives RW

/**
 * Fetch a workflow template by id. Subject to `accessibleSpaces`
 * authz — returns `NotFound` when the template exists but the
 * caller's chain isn't authorized for its space (avoids leaking
 * existence across tenant boundaries).
 */
final class GetWorkflowTool extends Tool with WorkflowToolSupport {
  type Input  = GetWorkflowInput
  type Output = GetWorkflowOutput
  val inputRW  = summon[RW[GetWorkflowInput]]
  val outputRW = summon[RW[GetWorkflowOutput]]
  val name = ToolName("get_workflow")
  val description =
    """Fetch a workflow template by id.
      |
      |`workflowId` is the template's id. Returns the full template — name, description,
      |step list, triggers, variable defs.""".stripMargin
  override val examples = List(ToolExample("fetch by id", GetWorkflowInput(workflowId = "wf-abc")))
  override val keywords = Set("workflow", "get", "describe")

  /** Internal retry budget for the not-yet-visible window. A template fetched
    * right after `create_workflow` may not be queryable for a few hundred ms;
    * retrying inside the call absorbs that window rather than returning a
    * `NotFound` the agent re-fetches across a whole LLM turn. `0` disables. */
  protected def fetchAttempts: Int = 5
  protected def fetchRetryDelay: FiniteDuration = 150.millis

  override def executeResult(input: GetWorkflowInput, ctx: ToolContext): Task[ToolResult[GetWorkflowOutput]] =
    workflowHost(ctx) match {
      case Left(err) => Task.pure(ToolResult.failure(err))
      case Right(host) =>
        fetchTemplate(host, Id[WorkflowTemplate](input.workflowId), math.max(1, fetchAttempts)).flatMap {
          case None => Task.pure(ToolResult.success(GetWorkflowOutput.NotFound(input.workflowId)))
          case Some(template) =>
            authorizeAccess(host, template, ctx.chain).map {
              case Left(_)  => ToolResult.success(GetWorkflowOutput.NotFound(input.workflowId)) // hide cross-space existence
              case Right(t) => ToolResult.success(project(t))
            }
        }
    }

  /** Read the template, retrying past a brief not-yet-visible window before
    * giving up. Only a genuine miss (after all attempts) yields `None`. */
  private def fetchTemplate(host: WorkflowSigil,
                            id: Id[WorkflowTemplate],
                            attemptsLeft: Int): Task[Option[WorkflowTemplate]] =
    host.withDB(_.workflowTemplates.transaction(_.get(id))).flatMap {
      case found @ Some(_)            => Task.pure(found)
      case None if attemptsLeft > 1   => Task.sleep(fetchRetryDelay).flatMap(_ => fetchTemplate(host, id, attemptsLeft - 1))
      case None                       => Task.pure(None)
    }

  private def project(t: WorkflowTemplate): GetWorkflowOutput.Found =
    GetWorkflowOutput.Found(
      workflowId   = t._id.value,
      name         = t.name,
      enabled      = t.enabled,
      description  = t.description,
      space        = t.space.value,
      stepIds      = t.steps.map(_.id),
      triggerKinds = t.triggers.map(_.kind),
      variables    = t.variableDefs.map(v => GetWorkflowVariable(v.name, v.required)),
      tags         = t.tags.toList
    )
}
