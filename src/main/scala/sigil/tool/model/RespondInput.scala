package sigil.tool.model

import fabric.rw.*
import sigil.tool.ToolInput

/**
 * Input for the respond tool.
 *
 * `content` is the multipart-format reply (`▶Text`, `▶Code <lang>`, etc.)
 * documented on the tool's description. The `@pattern` annotation enforces
 * the first header at the JSON Schema level so grammar-constrained decoders
 * cannot emit a bare string.
 *
 * `title` is REQUIRED on every call and is listed *after* `content` so the
 * model writes its response first and then names what it wrote — the natural
 * cognitive order for titling.
 *
 *   - If the current conversation title still fits, pass it unchanged. The
 *     framework detects the no-op and suppresses the `TitleChange` event.
 *   - Otherwise (new conversation or topic has meaningfully shifted),
 *     propose a concise 3-6 word title. The framework emits a `TitleChange`
 *     event that UIs render and the view folds into a System frame.
 */
case class RespondInput(@pattern("""^▶[A-Z][A-Za-z0-9]*(\s+\S+)?\n""") content: String,
                        title: String) extends ToolInput derives RW
