package sigil.tool

import fabric.Json
import fabric.define.Definition

/**
 * Façade over [[WireSurface]]'s normalisation — preserved as the public
 * entry point for callers that don't need the full decode pipeline.
 *
 * Normalises JSON tool-call arguments before they're handed to fabric's
 * `RW.write` for typed materialisation. Two coercion families, both
 * walking the JSON alongside the input's [[Definition]] so the rewrite
 * is schema-aware:
 *
 *   - Empty-string-as-None for `Option[String]` fields; literal `"null"`
 *     on Opt(Str) → JSON null.
 *   - String-encoded scalars (`"168594932069"` / `"true"`) coerced to
 *     their declared type when the schema declares integer / number /
 *     boolean.
 *
 * Recurses through `Obj`, `Arr`, and `Opt` so deeply-nested fields
 * normalise consistently.
 */
object InputNormalizer {

  /** Walk `json` alongside `definition` and apply both coercion families.
    * Delegates to [[WireSurface]]'s shared walker. */
  def normalize(json: Json, definition: Definition): Json =
    WireSurface.normalize(json, definition.defType)
}
