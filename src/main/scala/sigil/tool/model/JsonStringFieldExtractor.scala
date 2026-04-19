package sigil.tool.model

import java.util.regex.Pattern

/**
 * Incremental extractor for a single named string field from a streaming JSON
 * object. Feeds in raw JSON chunks (as they arrive from a tool-call args stream)
 * and produces the unescaped contents of the named string field as decoded
 * deltas.
 *
 * Assumes the JSON is a small object with `field` as the (only) key, which is
 * the shape produced by a tool like `respond` whose input is a single string.
 * Characters before the opening `"` of the value are buffered until the literal
 * `{...<ws>"<field>"<ws>:<ws>"` prefix is seen, after which value bytes are
 * decoded and emitted incrementally. Characters after the closing `"` are
 * ignored.
 *
 * Escape handling decodes `\" \\ \/ \n \r \t \b \f \uXXXX` per JSON spec.
 */
final class JsonStringFieldExtractor(field: String) {
  import JsonStringFieldExtractor.*

  private var phase: Phase = Phase.Prefix
  private val prefixBuf = new StringBuilder
  private val prefixRegex =
    ("""\{\s*"""" + Pattern.quote(field) + """"\s*:\s*"""").r
  private var unicodeRemaining: Int = 0
  private val unicodeAcc = new StringBuilder

  /**
   * Feed a chunk of JSON. Returns any newly decoded value text.
   */
  def append(chunk: String): String = {
    val out = new StringBuilder
    var i = 0
    while (i < chunk.length) {
      val c = chunk.charAt(i)
      phase match {
        case Phase.Prefix =>
          prefixBuf.append(c)
          i += 1
          prefixRegex.findFirstMatchIn(prefixBuf).foreach { _ =>
            phase = Phase.Value
            prefixBuf.clear()
          }

        case Phase.Value =>
          i += 1
          c match {
            case '"' => phase = Phase.Done
            case '\\' => phase = Phase.Esc
            case other => out.append(other)
          }

        case Phase.Esc =>
          i += 1
          c match {
            case '"' => out.append('"'); phase = Phase.Value
            case '\\' => out.append('\\'); phase = Phase.Value
            case '/' => out.append('/'); phase = Phase.Value
            case 'n' => out.append('\n'); phase = Phase.Value
            case 'r' => out.append('\r'); phase = Phase.Value
            case 't' => out.append('\t'); phase = Phase.Value
            case 'b' => out.append('\b'); phase = Phase.Value
            case 'f' => out.append('\f'); phase = Phase.Value
            case 'u' =>
              unicodeRemaining = 4
              unicodeAcc.clear()
              phase = Phase.Unicode
            case other => out.append(other); phase = Phase.Value
          }

        case Phase.Unicode =>
          i += 1
          unicodeAcc.append(c)
          unicodeRemaining -= 1
          if (unicodeRemaining == 0) {
            try {
              val cp = Integer.parseInt(unicodeAcc.toString, 16)
              out.append(cp.toChar)
            } catch { case _: NumberFormatException => () }
            phase = Phase.Value
          }

        case Phase.Done =>
          i = chunk.length
      }
    }
    out.toString
  }

  def isDone: Boolean = phase == Phase.Done
}

object JsonStringFieldExtractor {
  private enum Phase { case Prefix, Value, Esc, Unicode, Done }
}
