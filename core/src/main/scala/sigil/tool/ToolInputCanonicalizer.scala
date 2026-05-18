package sigil.tool

import fabric.Json
import fabric.io.JsonFormatter
import fabric.rw.*
import sigil.storage.FileVersion

import java.nio.charset.StandardCharsets

/**
 * Produces deterministic comparison keys for tool inputs so two calls
 * that carry the same logical args — possibly serialized with fields
 * in different orders by the model — collapse to the same hash.
 *
 * Used by the per-participant `recentToolInvocations` projection
 * (duplicate detection in the prompt) and by the orchestrator's
 * hard-cap intercept on repeated identical dispatches.
 *
 * Hashing strategy:
 *   - Round-trip the input through `summon[RW[ToolInput]].read` so the
 *     polymorphic discriminator is included (`grep(...)` and
 *     `bash(...)` with the same field map don't collide).
 *   - Render via `Json.toKey`, which serialises object fields in
 *     sorted-key order recursively — that's fabric's canonical-form
 *     writer.
 *   - SHA-256 over the UTF-8 bytes.
 *
 * If the RW path throws (custom ToolInput shapes that don't round-trip
 * cleanly), fall back to a raw-string hash of `input.toString` so the
 * dedupe machinery degrades to identity comparison rather than
 * crashing the orchestrator.
 */
object ToolInputCanonicalizer {
  private val PreviewLimit: Int = 60

  /** Canonical sorted-key Json string for `input`. Returns
    * `Right(canonical)` on the structured path, `Left(fallback)` when
    * the RW projection threw and the caller wants to know it landed on
    * the textual fallback. */
  def canonicalForm(input: ToolInput): Either[String, String] =
    try Right(summon[RW[ToolInput]].read(input).toKey)
    catch { case _: Throwable => Left(input.toString) }

  /** Sorted-key canonical Json string for `input` — collapses Either
    * branches. Internal callers that only need a deterministic key
    * (hash inputs, dedupe keys) use this and don't care which path
    * produced it. */
  def canonical(input: ToolInput): String = canonicalForm(input) match {
    case Right(json)     => json
    case Left(fallback)  => fallback
  }

  /** Stable SHA-256 hash of the canonical form, hex-encoded. */
  def argsHash(input: ToolInput): String =
    FileVersion.hashOf(canonical(input).getBytes(StandardCharsets.UTF_8))

  /** Short human-readable preview of the args — uses compact
    * (non-canonical) JSON for readability so the agent reading the
    * prompt sees field names in their natural emit order rather than
    * the alphabetised hash form. Trimmed to [[PreviewLimit]] chars
    * with an ellipsis when truncated. Falls back to `input.toString`
    * when the RW projection throws. */
  def argsPreview(input: ToolInput): String = {
    val rendered =
      try JsonFormatter.Compact(summon[RW[ToolInput]].read(input))
      catch { case _: Throwable => input.toString }
    if (rendered.length <= PreviewLimit) rendered
    else rendered.take(PreviewLimit - 3) + "..."
  }

  /** Variant used when the caller already has the `Json` projection in
    * hand — avoids a redundant RW round-trip. */
  def argsHash(json: Json): String =
    FileVersion.hashOf(json.toKey.getBytes(StandardCharsets.UTF_8))
}
