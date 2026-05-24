package sigil.tooling.container

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolName}

/**
 * Persist an inline list of items as an immutable paginated
 * container and return its `itemsId`. The id can be passed to any
 * consumer tool that takes `itemsId: Id[ToolOutputNode]` (e.g.
 * `dispatch_workers`). Useful when the agent has identified a set
 * of items through reasoning rather than tool output and wants to
 * operate over them.
 */
case object CreateContainerTool extends Tool {
  type Input  = CreateContainerInput
  type Output = CreateContainerOutput
  val inputRW  = summon[RW[CreateContainerInput]]
  val outputRW = summon[RW[CreateContainerOutput]]

  val name = ToolName("create_container")
  val description =
    """Persist an inline list of items as a paginated container and return its containerId.
      |Pass the containerId to any consumer that takes one (e.g. dispatch_workers). Useful
      |when you've identified a set of items through reasoning rather than tool output
      |(for example the user said "operate over these four files") and want to compose
      |that list with other container-shaped tools.""".stripMargin
  override val keywords = Set(
    "container", "items", "list", "persist", "create", "stage",
    "materialize", "from list", "from items"
  )


  override def executeOutput(input: CreateContainerInput,
                             ctx: ToolContext): Task[CreateContainerOutput] =
    ContainerSupport.persistItems(ctx.sigil, ctx.conversation.id, input.items).map {
      case (itemsId, count) => CreateContainerOutput(itemsId = itemsId, itemCount = count)
    }
}
