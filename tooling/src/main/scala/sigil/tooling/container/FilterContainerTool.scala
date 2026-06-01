package sigil.tooling.container

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolName, ToolResult}

/**
 * Filter an existing container into a new derived container. The source
 * container is untouched — containers are immutable. The returned
 * `itemsId` references a fresh container holding only the rows whose
 * payload satisfies the filter.
 */
case object FilterContainerTool extends Tool {
  type Input  = FilterContainerInput
  type Output = CreateContainerOutput
  val inputRW  = summon[RW[FilterContainerInput]]
  val outputRW = summon[RW[CreateContainerOutput]]

  val name = ToolName("filter_container")
  val description =
    """Filter an existing container into a new derived container, leaving the source
      |untouched. Returns the new containerId + count. Supply ONE filter:
      |  - `field` + `regex`  — regex on the value at the dotted `field` (e.g. field="filePath", regex="\\.scala$")
      |  - `field` + `equals` — value at `field` equals this string
      |  - `field` alone      — value at `field` is present / truthy
      |  - `contains`         — substring anywhere in the row's JSON
      |
      |Narrow a result set before acting on it:
      |  grep(pattern)                              → C1
      |  filter_container(C1, contains: "core/")    → C2
      |  dispatch_workers(itemsId = C2, ...)""".stripMargin
  override val keywords = Set(
    "filter", "container", "narrow", "subset", "select", "where",
    "predicate", "match", "search"
  )

  /** Resolve the flat scalar args into a [[ContainerPredicate]], or a
    * didactic message naming what to supply (the recoverable failure the
    * agent reads and self-corrects on — no nested-union to fumble). */
  private def resolvePredicate(in: FilterContainerInput): Either[String, ContainerPredicate] =
    (in.regex.filter(_.nonEmpty), in.field.filter(_.nonEmpty),
     in.equals.filter(_.nonEmpty), in.contains.filter(_.nonEmpty)) match {
      case (Some(rx), Some(f), _, _)    => Right(ContainerPredicate.RegexMatch(f, rx))
      case (Some(_), None, _, _)        => Left("`regex` needs a `field` — the dotted path whose value the regex matches against")
      case (None, Some(f), Some(eq), _) => Right(ContainerPredicate.JsonPath(f, Some(fabric.str(eq))))
      case (None, Some(f), None, _)     => Right(ContainerPredicate.JsonPath(f, None))
      case (None, None, _, Some(c))     => Right(ContainerPredicate.Contains(c))
      case _                            => Left("provide one filter: `contains` (substring), or `field` (optionally with `regex` / `equals`)")
    }

  override def executeResult(input: FilterContainerInput,
                             ctx: ToolContext): Task[ToolResult[CreateContainerOutput]] =
    resolvePredicate(input) match {
      case Left(msg) =>
        Task.pure(ToolResult.failure(
          message = s"filter_container: $msg.",
          hint    = Some("Filters are flat scalar args — no nested predicate object to fill.")
        ))
      case Right(predicate) =>
        ContainerSupport.readItems(ctx.sigil, ctx.conversation.id, input.sourceId).flatMap { rows =>
          val filtered = rows.filter(r => ContainerSupport.evaluate(predicate, r.payload))
          val payloads = filtered.sortBy(r => (r.level, r.ordinal)).map(_.payload)
          ContainerSupport.persistItems(ctx.sigil, ctx.conversation.id, payloads).map {
            case (itemsId, count) => ToolResult.success(CreateContainerOutput(itemsId = itemsId, itemCount = count))
          }
        }
    }
}
