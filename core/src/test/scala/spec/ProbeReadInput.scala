package spec

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for [[ProbeReadTool]] — a single opaque token that both
 * distinguishes the canonical args hash (so specs can drive the
 * duplicate-call cap) and is echoed back in the result text (so specs
 * can assert which results reached the rendered prompt).
 */
final case class ProbeReadInput(probe: String) extends ToolInput derives RW
