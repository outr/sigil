package sigil.tool

import fabric.define.DefType

/**
 * Registration-time check that a tool's [[Tool.paginate]] flag is
 * consistent with its input schema. Tools that declare `paginate =
 * true` outside of the [[sigil.tool.output.PaginatedTool]] framework
 * path must expose at least one recognised pagination field so the
 * agent has a way to ask for the next page; the framework rejects
 * the registration otherwise.
 *
 * [[sigil.tool.output.PaginatedTool]] subclasses are exempt — the
 * framework owns their pagination via `next_page` /
 * `query_tool_output`, so their input schema correctly omits
 * pagination fields.
 */
object PaginationValidator {

  /** Recognised pagination field names. A `paginate = true` tool's
    * input schema must contain at least one of these (unless it
    * extends [[sigil.tool.output.PaginatedTool]]). */
  val PaginationFieldNames: Set[String] =
    Set("offset", "limit", "cursor", "page", "pageSize", "pageToken")

  /** Validate one tool. Returns `Left(reason)` when the declaration
    * is inconsistent with the schema, `Right(())` otherwise. */
  def validate(tool: Tool): Either[String, Unit] = {
    if (!tool.paginate) Right(())
    else if (tool.isInstanceOf[sigil.tool.output.PaginatedTool[?, ?]]) Right(())
    else tool.inputDefinition.defType match {
      case DefType.Obj(fields) =>
        val present = fields.keys.toSet.intersect(PaginationFieldNames)
        if (present.nonEmpty) Right(())
        else Left(
          s"Tool '${tool.name.value}' declares paginate = true but its input schema exposes no " +
            s"pagination field. Add one of: ${PaginationFieldNames.toList.sorted.mkString(", ")}, " +
            "or extend sigil.tool.output.PaginatedTool to use the framework's next_page / " +
            "query_tool_output navigation."
        )
      case other =>
        Left(
          s"Tool '${tool.name.value}' declares paginate = true but its input schema is not an " +
            s"object (got ${other.getClass.getSimpleName}); paginated tools must accept an " +
            "object payload that carries a pagination field."
        )
    }
  }

  /** Validate a roster, raising on the first violation. */
  def validateAll(tools: Iterable[Tool]): Unit = {
    tools.foreach { t =>
      validate(t) match {
        case Right(_)     => ()
        case Left(reason) => throw new IllegalStateException(reason)
      }
    }
  }
}
