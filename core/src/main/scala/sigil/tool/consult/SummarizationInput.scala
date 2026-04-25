package sigil.tool.consult

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input schema for [[SummarizationTool]] — the structured return the
 * consulted model populates when producing a [[sigil.conversation.ContextSummary]].
 *
 * `summary` is the compact, self-contained replacement text that will
 * stand in for the compressed frames in every future turn.
 * `tokenEstimate` is the model's own estimate of its own output length;
 * the curator trusts this for downstream budget math.
 */
case class SummarizationInput(summary: String,
                              tokenEstimate: Int) extends ToolInput derives RW
