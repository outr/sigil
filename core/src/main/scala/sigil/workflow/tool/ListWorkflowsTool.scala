package sigil.workflow.tool

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{DiscoverySpec, Effect, Freshness, Tool, ToolExample, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

case class ListWorkflowsInput(tag: Option[String] = None) extends ToolInput derives RW

/**
 * List every workflow template the caller can see — filtered by
 * `accessibleSpaces` so cross-tenant isolation holds. `tag`
 * (optional) narrows to templates carrying a matching tag.
 */
final class ListWorkflowsTool extends Tool with WorkflowToolSupport {
  type Input  = ListWorkflowsInput
  type Output = ListWorkflowsOutput
  val inputRW  = summon[RW[ListWorkflowsInput]]
  val outputRW = summon[RW[ListWorkflowsOutput]]
  override val name = ToolName("list_workflows")
  override val description =
    """List the workflow templates visible to the caller (filtered by accessible spaces).
      |
      |`tag` (optional) restricts the result to templates carrying that tag.
      |Returns each template's id, name, description, step count, and whether it's enabled.""".stripMargin
  override val examples = List(
    ToolExample("list every visible workflow", ListWorkflowsInput()),
    ToolExample("filter by tag", ListWorkflowsInput(tag = Some("nightly")))
  )
  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(keywords = Set("workflow", "list", "find"))
  )

  override def executeResult(input: ListWorkflowsInput, ctx: ToolContext): Task[ToolResult[ListWorkflowsOutput]] =
    workflowHost(ctx) match {
      case Left(err) => Task.pure(ToolResult.failure(err))
      case Right(host) =>
        for {
          allowed <- host.accessibleSpaces(ctx.chain)
          all <- host.withDB(_.workflowTemplates.transaction(_.list))
          allowedSpaceValues = allowed.map(_.value) + sigil.GlobalSpace.value
          filtered = all.toList
            .filter(t => allowedSpaceValues.contains(t.space.value))
            .filter(t => input.tag.forall(t.tags.contains))
        } yield ToolResult.success(ListWorkflowsOutput(filtered.map { t =>
          WorkflowSummary(
            workflowId  = t._id.value,
            name        = t.name,
            enabled     = t.enabled,
            stepCount   = t.steps.size,
            description = t.description
          )
        }))
    }
}
