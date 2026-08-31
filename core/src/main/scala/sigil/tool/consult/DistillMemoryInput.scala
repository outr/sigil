package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Typed shape for the memory-distillation consult
 * ([[DistillMemoryTool]]).
 *
 *   - `summary` — one line capturing the fact's core: what a reader
 *     scanning a list must see to know this memory exists and what it
 *     holds.
 *   - `retrievalText` — optional self-contained rewrite of the fact
 *     for retrieval indexing (entities named explicitly, pronouns
 *     resolved, relationships spelled out). Omitted when the fact is
 *     already self-contained.
 */
case class DistillMemoryInput(summary: String,
                              retrievalText: Option[String] = None)
  extends ToolInput derives RW
