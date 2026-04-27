package sigil.script

import fabric.Json
import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[UpdateScriptToolTool]] — modify the body, description,
 * parameters schema, or keywords of an existing [[ScriptTool]].
 *
 * `name` identifies the target. Any field left `None` keeps the
 * stored value. The tool's `space` is fixed for the record's
 * lifetime (single-assignment rule); to surface the same script
 * under a different space, create a new record with that space.
 */
case class UpdateScriptToolInput(name: String,
                                 description: Option[String] = None,
                                 code: Option[String] = None,
                                 parameters: Option[Json] = None,
                                 keywords: Option[Set[String]] = None) extends ToolInput derives RW
