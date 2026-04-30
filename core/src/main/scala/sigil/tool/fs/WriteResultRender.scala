package sigil.tool.fs

import fabric.{Json, num, obj, str}
import sigil.storage.WriteResult

/**
 * Render a [[WriteResult]] from a safe-edit operation into the JSON
 * payload `write_file` / `edit_file` emit as their tool result.
 *
 * Shapes:
 *
 *   - [[WriteResult.Written]] — `{ result: "written", hash, bytesWritten }`
 *     where `bytesWritten` is the size of the content the caller
 *     supplied (UTF-8 byte length).
 *   - [[WriteResult.Stale]] — `{ result: "stale", currentHash,
 *     currentContent }` with the file's freshest contents (decoded
 *     UTF-8) so the agent can re-evaluate its edit without a
 *     separate read tool call.
 *   - [[WriteResult.NotFound]] — `{ result: "not_found" }`.
 */
private[fs] object WriteResultRender {
  def apply(result: WriteResult, attemptedContent: String): Json = result match {
    case WriteResult.Written(version) =>
      obj(
        "result" -> str("written"),
        "hash" -> str(version.hash),
        "bytesWritten" -> num(attemptedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong)
      )
    case WriteResult.Stale(current) =>
      obj(
        "result" -> str("stale"),
        "currentHash" -> str(current.version.hash),
        "currentContent" -> str(current.asText)
      )
    case WriteResult.NotFound =>
      obj("result" -> str("not_found"))
  }
}
