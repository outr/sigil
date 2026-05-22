package sigil.tool

import fabric.rw.*

/**
 * Standard [[ToolOutput]] for tools whose result is rendered text —
 * a status line, a human-readable summary, a free-form answer.
 *
 * The framework serialises it as `{"text": "…"}` into the
 * `ToolResults` event's `typed` Json. Tools with a structured payload
 * define their own `Output` case class instead; this is the shared
 * shape for the large set of tools whose result is just prose.
 */
case class TextToolOutput(text: String) extends ToolOutput derives RW
