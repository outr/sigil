package sigil.provider

/**
 * Defensive sanitizer for XML-format tool-call syntax that some
 * models occasionally embed inside structured content fields when
 * mode-mixing the JSON `tool_calls` protocol with their training-
 * time XML conventions (Anthropic-style `<tool_call>` /
 * `<function=…>` shapes show up in Qwen, Gemma, and other
 * open-source local models).
 *
 * llama.cpp's grammar-constrained mode pins the OUTER envelope to
 * valid JSON but cannot constrain the value of a string field —
 * so `RespondInput.content` can carry `"<tool_call><function=foo>…"`
 * inside a structurally-valid JSON tool-call argument. Without
 * sanitization that raw XML leaks to the user as text.
 *
 * The sanitizer is a pure function: scan the candidate content for
 * the two leak shapes, replace each matched span with a single-line
 * placeholder, and return the cleaned content alongside the list of
 * removed spans. Call sites that publish a Message (the orchestrator's
 * atomic-respond settle path + `RespondTool.executeTyped`) apply
 * this before parsing the content into ResponseContent blocks.
 */
object XmlToolCallSanitizer {

  /** Placeholder substituted for each removed XML tool-call span. */
  val Placeholder: String = "[blocked: XML tool-call syntax in content — see model diagnostics]"

  /** Matches either a `<tool_call>…</tool_call>` block or a bare
    * `<function=…>…</function>` block. Closing tags are optional —
    * a model that emitted only an opening tag still gets sanitized so
    * the partial XML doesn't render. Case-insensitive; multiline. */
  private val LeakRegex =
    raw"""(?is)<tool_call\b[^>]*>.*?(?:</tool_call>|\z)|<function=[^>]+>.*?(?:</function>|\z)""".r

  /** Result of a sanitization pass.
    *
    * @param content     the sanitized text with placeholder substitutions
    * @param leakedSpans the original matched substrings (verbatim, in
    *                    document order); empty when no leak fired
    */
  case class Result(content: String, leakedSpans: List[String])

  /** Scan `content` for the XML leak patterns. When matches are found,
    * replace each with [[Placeholder]] and return the matched spans so
    * callers can emit a diagnostic notice. When no matches, returns
    * `(content, Nil)` unchanged. */
  def sanitize(content: String): Result = {
    val matches = LeakRegex.findAllMatchIn(content).toList
    if (matches.isEmpty) Result(content, Nil)
    else Result(
      content     = LeakRegex.replaceAllIn(content, java.util.regex.Matcher.quoteReplacement(Placeholder)),
      leakedSpans = matches.map(_.matched)
    )
  }
}
