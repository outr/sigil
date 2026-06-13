package sigil.workflow.tool

import fabric.rw.*
import lightdb.id.Id
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolExample, ToolInput, ToolName, ToolResult}
import sigil.workflow.*

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
      workflowId  = t._id.value,
      name        = t.name,
      enabled     = t.enabled,
      description = t.description,
      space       = t.space.value,
      steps       = t.steps.flatMap(toSpec),
      triggers    = t.triggers,
      variables   = t.variableDefs.map(v => GetWorkflowVariable(v.name, v.required)),
      tags        = t.tags.toList
    )

  /** Reverse of [[WorkflowStepSpec.lower]]: project a stored
    * [[WorkflowStepInput]] IR node back to the flat, `kind`-tagged
    * [[WorkflowStepSpec]](s) `update_workflow` accepts. Nested Loop bodies /
    * Parallel branches are flattened back out as their own top-level entries,
    * referenced by id — the same flat shape the agent originally authored.
    * App-defined `WorkflowStepInput` subtypes (no `WorkflowStepKind`) can't be
    * expressed as a spec and are omitted. */
  private def toSpec(step: WorkflowStepInput): List[WorkflowStepSpec] = step match {
    case s: JobStepInput =>
      List(WorkflowStepSpec(
        id = s.id,
        kind = WorkflowStepKind.Job,
        name = s.name,
        prompt = s.prompt,
        tool = s.tool,
        arguments = s.arguments.flatMap(a => scala.util.Try(fabric.io.JsonParser(a)).toOption),
        output = s.output,
        complexity = s.complexity,
        tools = s.tools,
        continueOnError = s.continueOnError,
        retryCount = s.retryCount,
        retryDelayMs = s.retryDelayMs
      ))
    case s: ConditionStepInput =>
      List(WorkflowStepSpec(
        id = s.id,
        kind = WorkflowStepKind.Condition,
        name = s.name,
        expression = Some(s.expression),
        onTrue = Some(s.onTrue),
        onFalse = Some(s.onFalse)
      ))
    case s: ApprovalStepInput =>
      List(WorkflowStepSpec(
        id = s.id,
        kind = WorkflowStepKind.Approval,
        name = s.name,
        prompt = Some(s.prompt),
        options = s.options,
        output = s.output,
        timeoutMs = s.timeoutMs,
        timeoutAction = Some(s.timeoutAction)
      ))
    case s: ParallelStepInput =>
      WorkflowStepSpec(
        id = s.id,
        kind = WorkflowStepKind.Parallel,
        name = s.name,
        branchStepIds = s.branches.map(_.map(_.id)),
        joinMode = Some(s.joinMode),
        output = s.output
      ) :: s.branches.flatten.flatMap(toSpec)
    case s: LoopStepInput =>
      WorkflowStepSpec(
        id = s.id,
        kind = WorkflowStepKind.Loop,
        name = s.name,
        over = Some(s.over),
        itemVariable = Some(s.itemVariable),
        bodyStepIds = s.body.map(_.id),
        output = s.output
      ) :: s.body.flatMap(toSpec)
    case s: SubWorkflowStepInput =>
      List(WorkflowStepSpec(
        id = s.id,
        kind = WorkflowStepKind.SubWorkflow,
        name = s.name,
        workflowId = Some(s.workflowId),
        variables = s.variables,
        output = s.output
      ))
    case s: TriggerStepInput =>
      List(WorkflowStepSpec(
        id = s.id,
        kind = WorkflowStepKind.Trigger,
        name = s.name,
        trigger = Some(s.trigger),
        output = s.output
      ))
    case _ => Nil
  }
}
