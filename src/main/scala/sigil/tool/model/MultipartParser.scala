package sigil.tool.model

import fabric.io.JsonParser
import fabric.rw.*

import scala.util.Try
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
 *   - `▶Heading`     → [[ResponseContent.Heading]]
 *   - `▶Field`       → [[ResponseContent.Field]] (body is a JSON payload)
 *   - `▶Divider`     → [[ResponseContent.Divider]] (no body)
 *   - `▶Options`     → [[ResponseContent.Options]] (body is a JSON payload)
 *
 * Unknown block types, and JSON-bodied blocks whose body fails to parse,
 * fall back to `Text` carrying the raw body. Content before the first header
 * is silently dropped (the model is instructed to start with a header).
 * Empty blocks are skipped, except `▶Divider`, which emits even without body.
 */
object MultipartParser {
  private val Header: Regex = """^▶([A-Za-z][A-Za-z0-9]*)(?:\s+(\S+))?$""".r

  def parse(content: String): Vector[ResponseContent] = {
    val blocks = Vector.newBuilder[ResponseContent]
    var current: Option[(String, Option[String])] = None
    val buf = new StringBuilder

    def flush(): Unit = current.foreach { case (typeName, arg) =>
      val body = buf.toString.stripPrefix("\n").stripSuffix("\n")
      if (body.nonEmpty || typeName == "Divider") blocks += materialize(typeName, arg, body)
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
      case "Text" => ResponseContent.Text(body)
      case "Markdown" => ResponseContent.Markdown(body)
      case "Code" => ResponseContent.Code(body, arg)
      case "Heading" => ResponseContent.Heading(body)
      case "Field" => parseField(body).getOrElse(ResponseContent.Text(body))
      case "Divider" => ResponseContent.Divider
      case "Options" => parseOptions(body).getOrElse(ResponseContent.Text(body))
      case _ => ResponseContent.Text(body)
    }

  private def parseOptions(body: String): Option[ResponseContent.Options] =
    Try(JsonParser(body).as[OptionsPayload]).toOption.map { p =>
      ResponseContent.Options(prompt = p.prompt, options = p.options, allowMultiple = p.allowMultiple)
    }

  private def parseField(body: String): Option[ResponseContent.Field] =
    Try(JsonParser(body).as[FieldPayload]).toOption.map { p =>
      ResponseContent.Field(label = p.label, value = p.value, icon = p.icon)
    }

  /**
   * Wire representation of an `▶Options` block body — kept separate from
   * [[ResponseContent.Options]] so the enum's RW (a polymorphic `oneOf`) isn't
   * re-entered when decoding the raw JSON payload.
   */
  private case class OptionsPayload(prompt: String, options: List[SelectOption], allowMultiple: Boolean = false) derives RW

  /**
   * Wire representation of a `▶Field` block body — same rationale as
   * [[OptionsPayload]].
   */
  private case class FieldPayload(label: String, value: String, icon: Option[String] = None) derives RW
}
