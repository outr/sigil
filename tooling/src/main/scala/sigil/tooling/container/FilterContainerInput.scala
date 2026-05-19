package sigil.tooling.container

import fabric.rw.*
import lightdb.id.Id
import sigil.tool.ToolInput
import sigil.tool.output.ToolOutputNode

/**
 * Input for [[FilterContainerTool]] — narrow an existing
 * container into a new derived container.
 *
 *   - `sourceId`  — the container to narrow. Unaffected by the
 *     call — containers are immutable.
 *   - `predicate` — see [[ContainerPredicate]].
 */
case class FilterContainerInput(sourceId: Id[ToolOutputNode],
                                predicate: ContainerPredicate)
  extends ToolInput derives RW
