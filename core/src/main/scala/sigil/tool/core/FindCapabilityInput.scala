package sigil.tool.core

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[FindCapabilityTool]]. `keywords` is a space-separated list of
 * lowercase alphanumeric keywords describing the desired capability. The
 * app's [[sigil.Sigil.findTools]] decides how to match them against the
 * tool catalog.
 *
 * The `@pattern` constraint enforces the normalized form at generation
 * time (grammar-constrained decoders like llama.cpp honor it), so
 * downstream code can match on `keywords` directly without case folding
 * or punctuation stripping.
 */
case class FindCapabilityInput(
  @description("Space-separated lowercase keywords describing the desired capability. Prefer multiple keywords for better match quality — 'send slack channel message' rather than just 'slack'. Only lowercase letters, digits, and single spaces are allowed (no punctuation).")
  @pattern("""^[a-z0-9]+( [a-z0-9]+)*$""")
  keywords: String
) extends ToolInput derives RW
