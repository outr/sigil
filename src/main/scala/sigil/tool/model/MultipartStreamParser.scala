package sigil.tool.model

import scala.util.matching.Regex

/**
 * Streaming counterpart to [[MultipartParser]]. Accepts incremental text deltas
 * (as they are decoded from a JSON string field) and emits structured events
 * for block starts and body deltas.
 *
 * Because block headers are line-anchored (`▶<TYPE>` on its own line), the
 * parser buffers one possibly-in-progress header line at a time. A body
 * newline is deferred one char so it can be dropped when it turns out to be
 * the body→header separator (matching [[MultipartParser]]'s strip-trailing-newline
 * behaviour).
 */
final class MultipartStreamParser {
  import MultipartStreamParser.*

  private val out = Vector.newBuilder[ToolStreamEvent]
  private val bodyBuf = new StringBuilder
  private var headerBuf: Option[StringBuilder] = None
  private var pendingNewline = false
  private var atLineStart = true
  private var inBlock = false

  /**
   * When the previous chunk ended with a `\` and the escape target hasn't
   * arrived yet. The next char determines whether this is a `\n` / `\r` /
   * `\t` / `\\` escape (consume both as the resolved char) or something
   * else (emit the `\` literally then process the char). See
   * [[MultipartParser.normalizeEscapes]] for the rationale — some
   * llama.cpp server builds emit newlines as literal `\n` byte pairs
   * rather than actual newline characters.
   */
  private var pendingBackslash = false

  /**
   * Feed a chunk of decoded body text. Returns the events produced by this chunk.
   */
  def append(chunk: String): Vector[ToolStreamEvent] = {
    out.clear()
    chunk.foreach(feedChar)
    flushBody()
    out.result()
  }

  /**
   * Signal end of input. Drops any trailing deferred newline (matches
   * [[MultipartParser]]'s trim). If a header line is in progress and never
   * terminated, it's dropped (headers must be on their own complete line).
   */
  def finish(): Vector[ToolStreamEvent] = {
    out.clear()
    if (pendingBackslash) {
      pendingBackslash = false
      processChar('\\')
    }
    headerBuf.foreach { hb =>
      val s = hb.toString
      if (Header.findFirstMatchIn(s).isEmpty && inBlock) {
        if (pendingNewline) bodyBuf.append('\n')
        bodyBuf.append(s)
        pendingNewline = false
      }
      headerBuf = None
    }
    flushBody()
    pendingNewline = false
    out.result()
  }

  /** Normalize literal escape sequences (`\n`, `\r`, `\t`, `\\`) that some
    * llama.cpp server builds emit where actual control characters were
    * intended. State carries across chunks via [[pendingBackslash]]. */
  private def feedChar(c: Char): Unit = {
    if (pendingBackslash) {
      pendingBackslash = false
      c match {
        case 'n'  => processChar('\n')
        case 'r'  => processChar('\r')
        case 't'  => processChar('\t')
        case '\\' => processChar('\\')
        case other =>
          processChar('\\')
          processChar(other)
      }
    } else if (c == '\\') {
      pendingBackslash = true
    } else {
      processChar(c)
    }
  }

  private def processChar(c: Char): Unit = headerBuf match {
    case Some(hb) =>
      if (c == '\n') {
        val line = hb.toString
        headerBuf = None
        atLineStart = true
        Header.findFirstMatchIn(line) match {
          case Some(m) =>
            pendingNewline = false
            flushBody()
            inBlock = true
            out += ToolStreamEvent.BlockStart(m.group(1), Option(m.group(2)))
          case None =>
            if (inBlock) {
              if (pendingNewline) { bodyBuf.append('\n'); pendingNewline = false }
              bodyBuf.append(line)
              pendingNewline = true
            }
        }
      } else {
        hb.append(c)
      }

    case None =>
      if (atLineStart && c == '▶') {
        headerBuf = Some(new StringBuilder().append(c))
        atLineStart = false
      } else if (c == '\n') {
        if (inBlock) {
          if (pendingNewline) bodyBuf.append('\n')
          pendingNewline = true
        }
        atLineStart = true
      } else {
        if (inBlock) {
          if (pendingNewline) { bodyBuf.append('\n'); pendingNewline = false }
          bodyBuf.append(c)
        }
        atLineStart = false
      }
  }

  private def flushBody(): Unit =
    if (bodyBuf.nonEmpty) {
      out += ToolStreamEvent.BlockDelta(bodyBuf.toString)
      bodyBuf.clear()
    }
}

object MultipartStreamParser {
  private val Header: Regex = """^▶([A-Za-z][A-Za-z0-9]*)(?:\s+(\S+))?$""".r
}
