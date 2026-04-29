package sigil.tooling

import org.eclipse.lsp4j.{TextEdit, WorkspaceEdit}

/**
 * Applies a server-side `WorkspaceEdit` to the local filesystem.
 *
 * LSP servers send these in two shapes:
 *
 *   - `changes: Map[URI, List[TextEdit]]` — the legacy flat shape;
 *     each TextEdit is a (range, newText) replacement on the
 *     identified document
 *   - `documentChanges: List[Either[TextDocumentEdit, ResourceOperation]]`
 *     — the modern shape adding create / rename / delete file ops
 *     alongside text edits, with optional version expectations
 *
 * Both arrive at `LspRecordingClient.applyEdit` as the result of the
 * agent calling `lsp_rename` / `lsp_code_action` etc. The applier
 * walks the structure, applies each operation in declaration order,
 * and reports success/failure back to the server.
 *
 * Default impl: [[PermissiveWorkspaceEditApplier]] (writes anywhere
 * on disk). Apps that want sandboxing wrap with
 * [[SandboxedWorkspaceEditApplier]] or supply their own.
 */
trait WorkspaceEditApplier {
  /** Apply the edit; return true if every operation succeeded. */
  def apply(edit: WorkspaceEdit): Boolean
}

object WorkspaceEditApplier {

  /** Apply a list of TextEdits to a string. The protocol contract is
    * "non-overlapping ranges" — we sort descending and apply
    * back-to-front so earlier edits don't shift later edit ranges.
    * lsp4j's rename / code-action results follow this convention. */
  def applyTextEdits(text: String, edits: List[TextEdit]): String = {
    if (edits.isEmpty) return text
    val sorted = edits.sortBy { e =>
      val s = e.getRange.getStart
      (-s.getLine, -s.getCharacter)
    }
    val builder = new StringBuilder(text)
    sorted.foreach { e =>
      val start = lineCharOffset(text, e.getRange.getStart.getLine, e.getRange.getStart.getCharacter)
      val end = lineCharOffset(text, e.getRange.getEnd.getLine, e.getRange.getEnd.getCharacter)
      builder.replace(start, end, Option(e.getNewText).getOrElse(""))
    }
    builder.toString
  }

  /** Convert a (line, character) position to a 0-based offset in the
    * full string. Both inputs are 0-based per LSP convention. UTF-16
    * code-unit semantics — matches what most servers send for
    * Scala / TypeScript / JavaScript. */
  private def lineCharOffset(text: String, line: Int, character: Int): Int = {
    var off = 0
    var l = 0
    while (l < line && off < text.length) {
      val nl = text.indexOf('\n', off)
      if (nl < 0) {
        return text.length
      }
      off = nl + 1
      l += 1
    }
    math.min(off + character, text.length)
  }
}
