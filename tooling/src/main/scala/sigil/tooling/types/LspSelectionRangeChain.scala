package sigil.tooling.types

import fabric.rw.*

/**
 * Selection-range chain for one input position — the progressively
 * larger semantic ranges enclosing the cursor (innermost first).
 */
case class LspSelectionRangeChain(ranges: List[LspRange]) derives RW
