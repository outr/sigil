package sigil.workflow.tool

import fabric.{obj, str}
import fabric.rw.*
import rapid.Task
import sigil.{GlobalSpace, SpaceId}
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}
import sigil.workflow.{WorkflowStepKind, WorkflowStepSpec, WorkflowTemplate, WorkflowTrigger}

case class CreateWorkflowInput(name: String,
                               description: Option[String] = None,
                               steps: List[WorkflowStepSpec] = Nil,
                               triggers: List[WorkflowTrigger] = Nil,
                               variableDefs: List[strider.WorkflowVariable] = Nil,
                               tags: List[String] = Nil)
  extends ToolInput derives RW

/**
 * Persist a new [[WorkflowTemplate]]. The agent supplies the steps as flat,
 * `kind`-tagged [[WorkflowStepSpec]]s (lowered to the engine's
 * [[sigil.workflow.WorkflowStepInput]] IR), plus [[WorkflowTrigger]]s — fabric
 * round-trips them through the tool-call wire format with full field schema.
 *
 * The created template is scoped to the calling agent's caller
 * space (the head of the chain's `accessibleSpaces`). For
 * `GlobalSpace`-only callers (the framework default), the
 * template is global.
 */
final class CreateWorkflowTool extends Tool with WorkflowToolSupport {
  type Input = CreateWorkflowInput
  type Output = TextToolOutput
  val inputRW = summon[RW[CreateWorkflowInput]]
  val outputRW = summon[RW[TextToolOutput]]
  override val name = ToolName("create_workflow")
  override val description =
    """Create a new workflow template.
      |
      |`name` is the template's identifier. `steps` is a flat list of steps; each step has a
      |`kind` (Job / Condition / Approval / Parallel / Loop / SubWorkflow / Trigger) and fills the
      |fields for that kind. Job: `tool` + `arguments` (a JSON OBJECT holding every tool parameter)
      |or `prompt`, capturing into `output`. Loop: `over` (a variable — e.g. a prior Job's `output`,
      |coerced from text) plus
      |`bodyStepIds` (ids of steps in this same list to run per item, binding `itemVariable`).
      |Parallel: `branchStepIds`. Reference {{output}} variables in later steps' arguments/prompts.
      |`triggers` registers external firing conditions (conversation message, time / cron, webhook,
      |cross-workflow event, plus app-defined ones).
      |
      |Returns the persisted template's id.""".stripMargin
  override val examples = List(
    ToolExample(
      "discovery-as-a-stage: find files, act on each",
      CreateWorkflowInput(
        name = "process-matches",
        steps = List(
          WorkflowStepSpec(
            id = "find",
            kind = WorkflowStepKind.Job,
            tool = Some("grep"),
            arguments = Some(obj("pattern" -> str("TODO"), "path" -> str("/src"))),
            output = Some("hits")),
          WorkflowStepSpec(
            id = "each",
            kind = WorkflowStepKind.Loop,
            over = Some("hits"),
            itemVariable = Some("file"),
            bodyStepIds = List("act")),
          WorkflowStepSpec(id = "act", kind = WorkflowStepKind.Job, tool = Some("read_file"), arguments = Some(obj("path" -> str("{{file}}"))))
        )
      )
    )
  )
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
    discovery = DiscoverySpec(keywords = Set("workflow", "create", "compose", "automation"))
  )

  override def executeResult(input: CreateWorkflowInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] = withHostTyped(ctx) { host =>
    WorkflowStepSpec.lower(input.steps, input.variableDefs.map(_.name).toSet) match {
      case Left(errors) => Task.pure(ToolResult.failure(WorkflowStepSpec.violationMessage(errors)))
      case Right(steps) =>
        validateStepToolArgs(host, input.steps).flatMap {
          case argErrors if argErrors.nonEmpty =>
            Task.pure(ToolResult.failure("Workflow step arguments invalid:\n" + argErrors.mkString("\n")))
          case _ =>
            host.accessibleSpaces(ctx.chain).flatMap { spaces =>
              val callerSpace: SpaceId = spaces.headOption.getOrElse(GlobalSpace)
              val template = WorkflowTemplate(
                name = input.name,
                description = input.description,
                steps = steps,
                triggers = input.triggers,
                variableDefs = input.variableDefs,
                space = callerSpace,
                createdBy = Some(ctx.caller),
                conversationId = Some(ctx.conversation.id),
                tags = input.tags.toSet
              )
              host.withDB(_.workflowTemplates.transaction(_.insert(template))).map { stored =>
                ToolResult.success(TextToolOutput(s"Workflow '${input.name}' created with id ${stored._id.value}."))
              }
            }
        }
    }
  }
}
