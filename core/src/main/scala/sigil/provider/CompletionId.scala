package sigil.provider

import fabric.rw.*

/**
 * Identifies the single provider completion that emitted a tool call.
 *
 * A model that fires several calls at once produces one completion and
 * several [[sigil.event.ToolInvoke]]s. Replaying those as separate
 * assistant turns rewrites the batch into a sequence the model never
 * produced; carrying the completion's identity onto each invoke lets the
 * frame renderer rebuild the original batch — one assistant turn holding
 * every call, answered together.
 *
 * `None` on an invoke means "not attributable to a batch": framework
 * synthetics, orphan settles, and rows persisted before the field
 * existed. Those render one call per assistant turn, which is the
 * correct shape for a call that genuinely stood alone.
 */
case class CompletionId(value: String) extends AnyVal derives RW
