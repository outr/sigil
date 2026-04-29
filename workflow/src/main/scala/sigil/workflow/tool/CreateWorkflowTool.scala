package sigil.workflow.tool

import fabric.rw.*
import rapid.{Stream, Task}
import sigil.{GlobalSpace, SpaceId, TurnContext}
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.workflow.{WorkflowStepInput, WorkflowTemplate, WorkflowTrigger}

case class CreateWorkflowInput(name: String,
                               description: String = "",
                               steps: List[WorkflowStepInput] = Nil,
                               triggers: List[WorkflowTrigger] = Nil,
                               variableDefs: List[strider.WorkflowVariable] = Nil,
                               tags: List[String] = Nil) extends ToolInput derives RW

/**
 * Persist a new [[WorkflowTemplate]]. The agent supplies the
 * step shapes + triggers via the typed [[WorkflowStepInput]] /
 * [[WorkflowTrigger]] polymorphic types — fabric round-trips
 * them through the LLM's tool-call wire format with full
 * field schema.
 *
 * The created template is scoped to the calling agent's caller
 * space (the head of the chain's `accessibleSpaces`). For
 * `GlobalSpace`-only callers (the framework default), the
 * template is global.
 */
final class CreateWorkflowTool extends TypedTool[CreateWorkflowInput](
  name = ToolName("create_workflow"),
  description =
    """Create a new workflow template.
      |
      |`name` is the template's identifier. `steps` is the typed step list (Job / Condition /
      |Approval / Parallel / Loop / SubWorkflow / Trigger). `triggers` registers external
      |firing conditions (conversation message, time / cron, webhook, cross-workflow event,
      |plus app-defined ones).
      |
      |Returns the persisted template's id.""".stripMargin,
  examples = List(
    ToolExample(
      "minimal LLM-prompt workflow",
      CreateWorkflowInput(
        name = "summarize-input",
        steps = List(sigil.workflow.JobStepInput(
          id = "summarize",
          prompt = "Summarize: {{input}}",
          modelId = "openai/gpt-5.4-mini",
          output = "summary"
        ))
      )
    )
  ),
  keywords = Set("workflow", "create", "compose", "automation")
) with WorkflowToolSupport {
  override protected def executeTyped(input: CreateWorkflowInput, ctx: TurnContext): Stream[Event] = {
    workflowHost(ctx) match {
      case Left(err) => reply(ctx, err, isError = true)
      case Right(host) =>
        val task = host.accessibleSpaces(ctx.chain).flatMap { spaces =>
          val callerSpace: SpaceId = spaces.headOption.getOrElse(GlobalSpace)
          val template = WorkflowTemplate(
            name = input.name,
            description = input.description,
            steps = input.steps,
            triggers = input.triggers,
            variableDefs = input.variableDefs,
            space = callerSpace,
            createdBy = Some(ctx.caller),
            conversationId = Some(ctx.conversation.id),
            tags = input.tags.toSet
          )
          host.withDB(_.workflowTemplates.transaction(_.insert(template))).map { stored =>
            s"Workflow '${input.name}' created with id ${stored._id.value}."
          }
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
