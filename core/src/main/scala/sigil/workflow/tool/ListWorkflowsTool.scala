package sigil.workflow.tool

import fabric.rw.*
import lightdb.filter.*
import rapid.{Stream, Task}
import sigil.{Sigil, TurnContext}
import sigil.event.Event
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.workflow.WorkflowTemplate

case class ListWorkflowsInput(tag: Option[String] = None) extends ToolInput derives RW

/**
 * List every workflow template the caller can see — filtered by
 * `accessibleSpaces` so cross-tenant isolation holds. `tag`
 * (optional) narrows to templates carrying a matching tag.
 */
final class ListWorkflowsTool extends TypedTool[ListWorkflowsInput](
  name = ToolName("list_workflows"),
  description =
    """List the workflow templates visible to the caller (filtered by accessible spaces).
      |
      |`tag` (optional) restricts the result to templates carrying that tag.
      |Returns each template's id, name, description, step count, and whether it's enabled.""".stripMargin,
  examples = List(
    ToolExample("list every visible workflow", ListWorkflowsInput()),
    ToolExample("filter by tag", ListWorkflowsInput(tag = Some("nightly")))
  ),
  keywords = Set("workflow", "list", "find")
) with WorkflowToolSupport {
  override protected def executeTyped(input: ListWorkflowsInput, ctx: TurnContext): Stream[Event] = {
    workflowHost(ctx) match {
      case Left(err) => reply(ctx, err, isError = true)
      case Right(host) =>
        val task = for {
          allowed <- host.accessibleSpaces(ctx.chain)
          all <- host.withDB(_.workflowTemplates.transaction(_.list))
          allowedSpaceValues = allowed.map(_.value) + sigil.GlobalSpace.value
          filtered = all.toList
            .filter(t => allowedSpaceValues.contains(t.space.value))
            .filter(t => input.tag.forall(t.tags.contains))
        } yield {
          if (filtered.isEmpty) "No workflows visible."
          else filtered.map { t =>
            val flags = if (t.enabled) "" else " [disabled]"
            s"  [${t._id.value}] ${t.name}$flags — ${t.steps.size} step(s) — ${t.description}"
          }.mkString("\n")
        }
        Stream.force(task.map(text => reply(ctx, text)))
    }
  }
}
