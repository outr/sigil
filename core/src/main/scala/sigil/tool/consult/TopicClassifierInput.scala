package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the topic classifier — a single `kind` enum value. The set
 * of valid enum values is `["NoChange", "Refine", "New"]` plus each
 * prior topic label, built dynamically per request by
 * [[TopicClassifierTool]].
 */
case class TopicClassifierInput(kind: String) extends ToolInput derives RW
