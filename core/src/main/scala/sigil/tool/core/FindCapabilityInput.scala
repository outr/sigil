package sigil.tool.core

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[FindCapabilityTool]]. `keywords` is a free-form string
 * describing the desired capability. The tool normalises it
 * (lowercase, drop punctuation, collapse whitespace) before handing
 * it to the app's [[sigil.Sigil.findTools]], so the model can pass
 * snake_case identifiers, camelCase phrases, or mixed-case prose
 * without rejection.
 *
 * No `@pattern` annotation here — bug #52. JSON-Schema `pattern`
 * regexes aren't enforced by grammar-constrained decoders (llama.cpp
 * gates JSON shape, not string contents); we used to reject
 * snake_case input post-decode and the model would loop on the same
 * disallowed identifier. The tool normalises explicitly instead.
 */
case class FindCapabilityInput(
  @description("Words describing the desired capability. Prefer multiple terms for better match quality — 'send slack channel message' rather than just 'slack'. Snake_case / camelCase identifiers are accepted; the tool lowercases and splits on non-alphanumeric runs before matching.")
  keywords: String
) extends ToolInput derives RW
