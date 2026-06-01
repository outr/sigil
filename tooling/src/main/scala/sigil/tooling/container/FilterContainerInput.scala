package sigil.tooling.container

import fabric.rw.*
import lightdb.id.Id
import sigil.tool.ToolInput
import sigil.tool.output.ToolOutputNode

/**
 * Input for [[FilterContainerTool]] — narrow an existing container into a
 * new derived container.
 *
 * The filter is expressed as flat scalar args, not a nested predicate
 * union (#338 — a `oneOf` with a required nested field is a shape models
 * reliably can't produce). Supply ONE of, in priority order:
 *
 *   - `field` + `regex`  — regex applied to the stringified value at the
 *     dotted `field` path (e.g. `field = "filePath", regex = "\\.scala$"`).
 *   - `field` + `equals` — the value at `field` must equal this string.
 *   - `field` alone      — the value at `field` must merely be truthy.
 *   - `contains`         — substring match against the row payload's JSON
 *     rendering (the simplest "rows mentioning X anywhere").
 *
 * `sourceId` is the container to narrow; it's untouched (containers are
 * immutable). [[FilterContainerTool]] resolves these into a
 * [[ContainerPredicate]] internally, and returns a didactic failure if
 * none (or `regex` without `field`) is supplied.
 */
case class FilterContainerInput(sourceId: Id[ToolOutputNode],
                                contains: Option[String] = None,
                                field: Option[String] = None,
                                regex: Option[String] = None,
                                equals: Option[String] = None)
  extends ToolInput derives RW
