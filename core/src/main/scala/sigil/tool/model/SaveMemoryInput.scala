package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `save_memory`. The agent persists a fact for later
 * retrieval. `key` (optional) identifies a memory slot — passing
 * the same key again upserts the prior memory; omitting it appends
 * a new memory record. `summary` and `label` (optional) populate
 * the surfaced metadata on the persisted record.
 */
case class SaveMemoryInput(fact: String,
                           key: Option[String] = None,
                           label: Option[String] = None,
                           summary: Option[String] = None) extends ToolInput derives RW
