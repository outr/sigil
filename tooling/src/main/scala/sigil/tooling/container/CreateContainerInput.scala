package sigil.tooling.container

import fabric.Json
import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[CreateContainerTool]] — persist an inline list of
 * items as a paginated container so any consumer tool (e.g.
 * `dispatch_workers`) can take it by `itemsId`.
 *
 *   - `items` — the raw list. Each element becomes one container
 *     row in input order. Raw `List[Json]` is the documented JSON
 *     boundary; tool authors that want a typed schema for their
 *     items pass `outputSchema`-shaped JSON values here.
 */
case class CreateContainerInput(items: List[Json]) extends ToolInput derives RW
