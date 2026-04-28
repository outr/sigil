package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the `respond_options` tool — emit a structured
 * multiple-choice block as part of the agent's reply. Use when the
 * agent needs to ask a bounded question and offer the user a fixed set
 * of selectable answers.
 *
 * Markdown can't natively express interactive choice widgets, so this
 * is one of the small set of atomic content tools that exist alongside
 * the plain markdown content stream.
 *
 *   - `prompt` — the question text shown above the options.
 *   - `options` — the available choices. Order is preserved.
 *   - `allowMultiple` — `false` (default) means exactly one option may
 *     be selected; `true` means zero or more, and any option flagged
 *     `exclusive = true` cannot be combined with other selections.
 *
 * The user is always free to reply with natural language instead of
 * picking from the options — the framework does not require a
 * structured response.
 */
case class RespondOptionsInput(prompt: String,
                               options: List[SelectOption],
                               allowMultiple: Boolean = false) extends ToolInput derives RW
