package sigil.tool.model

import scala.util.matching.Regex

/**
 * Parses the multipart string format used by the respond tool into typed
 * ResponseContent blocks.
 *
 * Format: each block starts with a header line of the shape `▶<TYPE>` or
 * `▶<TYPE> <arg>`, on its own line. The block continues until the next header
 * or the end of input — there are no close markers.
 *
 * Recognised types:
 *   - `▶Text`        → [[ResponseContent.Text]]
 *   - `▶Markdown`    → [[ResponseContent.Markdown]]
 *   - `▶Code <lang>` → [[ResponseContent.Code]] with language
 *
 * Unknown block types fall back to `Text`. Content before the first header is
 * silently dropped (the model is instructed to start with a header). Empty
 * blocks are skipped.
 */
object MultipartParser {
  private val Header: Regex = """^▶([A-Za-z][A-Za-z0-9]*)(?:\s+(\S+))?$""".r

  def parse(content: String): Vector[ResponseContent] = {
    val blocks = Vector.newBuilder[ResponseContent]
    var current: Option[(String, Option[String])] = None
    val buf = new StringBuilder

    def flush(): Unit = current.foreach { case (typeName, arg) =>
      val body = buf.toString.stripPrefix("\n").stripSuffix("\n")
      if (body.nonEmpty) blocks += materialize(typeName, arg, body)
      buf.clear()
    }

    content.linesIterator.foreach {
      case Header(typeName, arg) =>
        flush()
        current = Some((typeName, Option(arg)))
      case line =>
        if (current.isDefined) {
          if (buf.nonEmpty) buf.append('\n')
          buf.append(line)
        }
    }
    flush()
    blocks.result()
  }

  private def materialize(typeName: String, arg: Option[String], body: String): ResponseContent =
    typeName match {
      case "Text"     => ResponseContent.Text(body)
      case "Markdown" => ResponseContent.Markdown(body)
      case "Code"     => ResponseContent.Code(body, arg)
      case _          => ResponseContent.Text(body)
    }
}
