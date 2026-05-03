package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for `save_memory`. The agent persists a fact for later
 * retrieval. `key` (optional) identifies a memory slot — passing
 * the same key again upserts the prior memory; omitting it appends
 * a new memory record. `summary` and `label` (optional) populate
 * the surfaced metadata on the persisted record.
 *
 * `permanence` (optional, `"Once"` | `"Always"`): the agent's hint
 * about whether the memory should pin (load every turn) or stay
 * topical. The framework's classifier still runs and may upgrade
 * a `Once` hint to pinned when it detects imperative phrasing in
 * the user's recent message ("always do X", "never delete files");
 * `Always` is preserved unconditionally. Omit to let the classifier
 * decide entirely.
 *
 * `space` (optional): the `value` of an accessible
 * [[sigil.SpaceId]] the memory should live in. When supplied and
 * resolvable, the framework persists the memory in that space and
 * the classifier's space decision is ignored. When omitted, the
 * classifier picks the most-specific applicable space (or signals
 * ambiguity via the agent's tool result).
 */
case class SaveMemoryInput(fact: String,
                           label: String,
                           summary: String,
                           key: Option[String] = None,
                           permanence: Option[String] = None,
                           space: Option[String] = None) extends ToolInput derives RW
