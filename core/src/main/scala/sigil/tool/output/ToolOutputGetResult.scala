package sigil.tool.output

import fabric.rw.*

/** Typed result for [[ToolOutputGetTool]]. Two states:
  *
  *   - `Found(outputId, contentType, size, returned, content)` — the
  *     full or sliced payload was retrieved. `returned` is the byte
  *     length of `content` (UTF-8 decoded); for slices it's the
  *     slice length, for full reads it equals `size`.
  *   - `NotFound(outputId, error)` — the StoredFile didn't exist
  *     or the caller's accessibleSpaces didn't authorize it.
  */
enum ToolOutputGetResult derives RW {
  case Found(outputId: String,
             contentType: String,
             size: Long,
             returned: Long,
             content: String)
  case NotFound(outputId: String, error: String)
}
