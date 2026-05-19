package sigil.tooling.container

import rapid.Task
import sigil.TurnContext
import sigil.tool.{ToolName, TypedOutputTool}

/**
 * Filter an existing container into a new derived container. The
 * source container is untouched — containers are immutable. The
 * returned `itemsId` references a fresh container holding only
 * the rows whose payload satisfies the predicate.
 */
case object FilterContainerTool extends TypedOutputTool[FilterContainerInput, CreateContainerOutput](
  name = ToolName("filter_container"),
  description =
    """Filter an existing container into a new derived container, leaving the source
      |untouched. Returns the new containerId + count.
      |Predicate options:
      |  - `Contains(text)`        — substring match against the payload's JSON rendering.
      |  - `JsonPath(path, equals?)` — dotted path lookup; with `equals`, value must match;
      |    without `equals`, value must merely be truthy.
      |  - `RegexMatch(field, pattern)` — regex against the stringified value at the path.
      |
      |Useful for "narrow this result set to the subset I want to operate on":
      |  grep(pattern)            → C1
      |  filter_container(C1, Contains("core/")) → C2
      |  dispatch_workers(C2, ...)""".stripMargin,
  keywords = Set(
    "filter", "container", "narrow", "subset", "select", "where",
    "predicate", "match", "search"
  )
) {
  override def paginate: Boolean = false

  override protected def executeTyped(input: FilterContainerInput,
                                      ctx: TurnContext): Task[CreateContainerOutput] =
    ContainerSupport.readItems(ctx.sigil, ctx.conversation.id, input.sourceId).flatMap { rows =>
      val filtered = rows.filter(r => ContainerSupport.evaluate(input.predicate, r.payload))
      val payloads = filtered.sortBy(r => (r.level, r.ordinal)).map(_.payload)
      ContainerSupport.persistItems(ctx.sigil, ctx.conversation.id, payloads).map {
        case (itemsId, count) => CreateContainerOutput(itemsId = itemsId, itemCount = count)
      }
    }
}
