package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the `respond_field` tool — emit a labeled key/value field
 * as part of the agent's reply. The workhorse for compact card-shaped
 * content (status summaries, news metadata, product attributes).
 *
 * Markdown can express bold-and-value (`**Status:** Ready`) but loses
 * the renderer's freedom to display this as an icon-prefixed row, a
 * Slack `field` element, or a horizontal field strip. This atomic tool
 * carries the structure the renderer needs.
 *
 *   - `label` — the field's label.
 *   - `value` — the field's value.
 *   - `icon` — optional semantic icon hint (renderer-defined; common
 *     hints: `article`, `clock`, `check`, `warn`).
 */
case class RespondFieldInput(label: String,
                             value: String,
                             icon: Option[String] = None) extends ToolInput derives RW
