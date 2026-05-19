package sigil.tool

/**
 * Detect schema-leaked placeholder values that agents sometimes send
 * as literal tool inputs.
 *
 * The agent reads its own tool schema (`"filePath": { "type":
 * "string", ... }`) and dispatches the call with `"filePath":
 * "string"` — the literal type-name token rather than a real value.
 * Without this guard the tool tries to operate on a file named
 * `string`, trips a `NoSuchFileException`, and surfaces a failure the
 * agent may misattribute to infrastructure rather than its own input.
 *
 * Detection is intentionally conservative to keep the false-positive
 * surface negligible:
 *
 *  - Exact-equality match only — the whole string must equal a
 *    type-name token. `"string"` is rejected; `"string interpolation"`
 *    or `"a string here"` is not.
 *
 *  - Field-name whitelist — fields whose semantic is "user-supplied
 *    content" (search strings, replacement strings, regex patterns,
 *    free-form text bodies, document content) are exempt. Searching
 *    for the literal word `"string"` in source code is a real refactor
 *    pattern.
 */
object PlaceholderInputDetector {

  /**
   * The seven JSON Schema primitive type-name tokens, exactly as the
   * spec writes them (lowercase). Matches the agent's most common
   * "read schema, paste type-name as value" failure mode.
   */
  val JsonSchemaTypeNames: Set[String] = Set(
    "string",
    "integer",
    "number",
    "boolean",
    "object",
    "array",
    "null"
  )

  /**
   * Field names whose semantic is "user-supplied content" — search
   * strings, regex patterns, replacement strings, free-form text
   * bodies, document content. A legitimate value here can plausibly
   * be the literal word `"string"` (or another type-name token), so
   * detection is skipped.
   */
  val UserSuppliedContentFieldNames: Set[String] = Set(
    "oldString",
    "newString",
    "newText",
    "pattern",
    "query",
    "text",
    "content"
  )

  /**
   * Detect a JSON-Schema type-name leak. Returns a human-readable
   * reason when `value` is exactly one of the spec's type-name tokens
   * AND `fieldName` is not in the user-supplied-content whitelist.
   */
  def detectSchemaTypeNameLeak(fieldName: String, value: String): Option[String] = {
    if (UserSuppliedContentFieldNames.contains(fieldName)) None
    else if (JsonSchemaTypeNames.contains(value))
      Some(s"value '$value' is a JSON Schema type name, not a real $fieldName value")
    else None
  }

  /**
   * Single-field entry point. Composes all detection passes (today
   * just [[detectSchemaTypeNameLeak]]; future shapes are added here
   * via `.orElse`). Returns `None` when the value passes, `Some(reason)`
   * when the value is a recognised placeholder leak.
   */
  def detect(fieldName: String, value: String): Option[String] =
    detectSchemaTypeNameLeak(fieldName, value)

  /**
   * Multi-field guard. Iterates `(fieldName, value)` pairs in order
   * and returns the first violation's didactic message, prefixed with
   * `"input rejected: "` and suffixed with a hint pointing the agent
   * at sources of real values.
   *
   * Tools call this in their `executeTyped` / `executeTypedResult` /
   * `executeStream` prelude — on `Some(reason)`, short-circuit with a
   * structured failure carrying that text; on `None`, run the actual
   * work.
   */
  def validateNoPlaceholders(fields: (String, String)*): Option[String] = {
    val it = fields.iterator.flatMap { case (name, value) =>
      detect(name, value).map { reason =>
        s"input rejected: $reason. Provide a real $name value from a prior tool result, " +
          "user input, or the conversation context."
      }
    }
    if (it.hasNext) Some(it.next()) else None
  }
}
