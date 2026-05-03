package sigil.conversation

import fabric.rw.*

/**
 * How a memory's load policy should be classified at save time.
 *
 *   - [[Once]]   — topical retrieval; the memory surfaces only when
 *                  the conversation's topic / keywords match. Default
 *                  for ordinary preferences and reference facts.
 *   - [[Always]] — pinned; the memory renders every turn, regardless
 *                  of topic. Reserved for imperative directives the
 *                  user has stated as rules ("always X", "never Y").
 *
 * Drives [[sigil.conversation.ContextMemory.pinned]]: `Always` →
 * `pinned = true`, `Once` → `pinned = false`. Used by the unified
 * [[sigil.tool.consult.ClassifyMemoryTool]] output and by agent
 * tools that take an explicit permanence override
 * ([[sigil.tool.model.SaveMemoryInput.permanence]]).
 */
enum Permanence derives RW {
  case Once
  case Always
}
