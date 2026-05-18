package sigil.tool.model

import fabric.rw.*
import sigil.conversation.{MemoryType, Permanence}
import sigil.conversation.MemoryType.given
import sigil.tool.ToolInput

/**
 * Input for `save_memory`. The agent persists a fact for later
 * retrieval. `key` (optional) identifies a memory slot — passing
 * the same key again upserts the prior memory; omitting it appends
 * a new memory record. `summary` and `label` populate the surfaced
 * metadata on the persisted record.
 *
 * `permanence` (optional, [[Permanence.Once]] | [[Permanence.Always]]):
 * the agent's hint about whether the memory should pin (load every
 * turn) or stay topical. The framework's classifier still runs and
 * may upgrade `Once` to pinned when it detects imperative phrasing
 * in the user's recent message ("always do X", "never delete files");
 * `Always` is preserved unconditionally. Omit to let the classifier
 * decide entirely.
 *
 * `space` (optional): the `value` of an accessible
 * [[sigil.SpaceId]] the memory should live in. When supplied and
 * resolvable, the framework persists the memory in that space and
 * the classifier's space decision is ignored. When omitted, the
 * classifier picks the most-specific applicable space (or signals
 * ambiguity via the agent's tool result).
 *
 * `keywords` (optional): agent-supplied retrieval tokens. The
 * classifier appends its own keywords; agent-supplied ones are
 * preserved.
 *
 * `memoryType` (optional, [[MemoryType.Fact]] by default): the
 * taxonomy bucket for downstream filtering / UI grouping. The
 * framework itself makes no behavioural decisions based on the type.
 */
case class SaveMemoryInput(fact: String,
                           label: String,
                           summary: String,
                           key: Option[String] = None,
                           permanence: Option[Permanence] = None,
                           space: Option[String] = None,
                           keywords: Vector[String] = Vector.empty,
                           memoryType: MemoryType = MemoryType.Fact,
                           /**
                            * Per-[[sigil.provider.Mode]] retrieval gate.
                            * Pass mode `name`s (e.g. `"coding"`, `"research"`)
                            * when the user's directive only applies in
                            * specific modes — "always create failing unit
                            * tests before fixing a bug" → `Set("coding")`.
                            * Empty (default) = the memory loads in every
                            * mode. Sigil bug #195 — keeps mode-specific
                            * directives from bloating the prompt budget
                            * of unrelated turns.
                            */
                           modeAffinity: Set[String] = Set.empty)
  extends ToolInput derives RW
