package sigil.tool.model

import org.commonmark.node.{
  AbstractVisitor, BlockQuote, BulletList, FencedCodeBlock, Heading, Image, Link, Node,
  OrderedList, Paragraph, Text => CMText, ThematicBreak
}
import org.commonmark.parser.Parser as CMParser
import spice.net.URL

/**
 * Parses an assembled markdown string into a `Vector[ResponseContent]`.
 *
 * Block-level mapping:
 *   - Fenced code blocks (```lang...```)     → `ResponseContent.Code(code, language?)`
 *   - Headings level 3+ (`###`, …)           → `ResponseContent.Heading(text)`
 *   - Headings level 1-2 (`#`, `##`)         → open a `ResponseContent.Card` whose
 *                                              `title` is the heading text and whose
 *                                              `sections` are every block that follows
 *                                              until the next H1/H2 (auto-Card grouping)
 *   - Thematic breaks (`---`)                → `ResponseContent.Divider`
 *   - Image-only paragraph (`![alt](url)`)   → `ResponseContent.Image(url, altText?)`
 *   - Bullet / ordered lists                 → `ResponseContent.ItemList(items, ordered)`
 *   - GitHub-style alert callout:
 *       `> [!Field icon="…"]`
 *       `> Label: Value`                    → `ResponseContent.Field(label, value, icon)`
 *     The directive line tolerates a missing leading `>` (some
 *     providers — DigitalOcean kimi, Google Gemini — drop it).
 *     Body lines accept either single-line `Label: Value` (split on
 *     first `:`) or two-line `Label:\nValue:` (key/value extracted).
 *   - Anything else                          → `ResponseContent.Markdown(rendered)`
 */
object MarkdownContentParser {
  private val parser = CMParser.builder().build()

  /**
   * `> [!Type ...]` or bare `[!Type ...]` directive on the first line
   * of a blockquote body. Captures the type name + any inline
   * attributes (`icon="..."`, `multi`, `(recoverable)`).
   */
  private val AlertDirective = """^\s*\[!([A-Za-z][A-Za-z0-9_-]*)\s*(.*?)\]\s*$""".r

  def parse(markdown: String): Vector[ResponseContent] = {
    if (markdown.trim.isEmpty) return Vector.empty
    val doc = parser.parse(markdown)

    sealed trait Item
    final case class Boundary(title: String) extends Item
    final case class Block(content: ResponseContent) extends Item

    val items = Vector.newBuilder[Item]
    var node = doc.getFirstChild
    while (node != null) {
      node match {
        case h: Heading if h.getLevel <= 2 =>
          items += Boundary(textOf(h))
        case h: Heading =>
          items += Block(ResponseContent.Heading(textOf(h)))
        case bq: BlockQuote =>
          parseAlert(bq) match {
            case Some(block) => items += Block(block)
            case None => blockFor(bq).foreach(b => items += Block(b))
          }
        case _ =>
          blockFor(node).foreach(b => items += Block(b))
      }
      node = node.getNext
    }

    val out = Vector.newBuilder[ResponseContent]
    var card: Option[(String, scala.collection.mutable.ArrayBuffer[ResponseContent])] = None

    // A heading with no following body just becomes a Heading block —
    // we only wrap into a Card when there's content under the title.
    // This keeps a standalone `## Section title` from collapsing to an
    // empty Card.
    def flushCard(): Unit = card.foreach { case (title, sections) =>
      if (sections.nonEmpty) out += Card(sections.toVector, title = Some(title))
      else out += ResponseContent.Heading(title)
    }

    items.result().foreach {
      case Boundary(title) =>
        flushCard()
        card = Some((title, scala.collection.mutable.ArrayBuffer.empty))
      case Block(b) =>
        card match {
          case Some((_, sections)) => sections += b
          case None => out += b
        }
    }
    flushCard()
    out.result()
  }

  /**
   * Recognise `> [!Type attrs...]\n> body...` GitHub alert callouts.
   * Returns Some[ResponseContent] when the directive maps to a known
   * structured block. Returns None for either non-alert blockquotes
   * or alerts whose type isn't one we extract — those fall through
   * to the generic markdown blockquote path.
   */
  private def parseAlert(bq: BlockQuote): Option[ResponseContent] = {
    val rendered = renderMarkdown(bq).stripPrefix("> ")
    val lines = rendered.split("\n").toList.map(_.stripPrefix("> "))
    if (lines.isEmpty) return None
    val directiveLine = lines.head.trim
    AlertDirective.findFirstMatchIn(directiveLine) match {
      case Some(m) =>
        val typ = m.group(1)
        val attrs = parseAttrs(m.group(2))
        val body = lines.tail.map(_.replaceFirst("^>\\s*", "")).filter(_.nonEmpty)
        typ.toLowerCase match {
          case "field" => parseFieldBody(body, attrs)
          case _ => None // unknown alert type — let caller fall through
        }
      case None => None
    }
  }

  /**
   * Extract `key="value"`, bare `flag`, and `(parenthetical)` markers
   * from the directive's attribute suffix.
   */
  private def parseAttrs(s: String): Map[String, String] = {
    val out = scala.collection.mutable.Map.empty[String, String]
    val keyEq = """(\w+)\s*=\s*"([^"]*)"""".r
    val parens = """\(\s*(\w+)\s*\)""".r
    keyEq.findAllMatchIn(s).foreach(m => out += (m.group(1).toLowerCase -> m.group(2)))
    parens.findAllMatchIn(s).foreach(m => out += (m.group(1).toLowerCase -> "true"))
    out.toMap
  }

  /**
   * Body shape accepted:
   *   `Label: Value` (single line, split on first `:`)
   *   `Label:` + `Value:` (two lines extracted by key)
   */
  private def parseFieldBody(body: List[String], attrs: Map[String, String]): Option[ResponseContent.Field] = {
    val icon = attrs.get("icon")
    if (body.isEmpty) return None
    if (body.size >= 2 && body(0).trim.toLowerCase.startsWith("label:") && body(1).trim.toLowerCase.startsWith("value:")) {
      val label = body(0).trim.drop("label:".length).trim
      val value = body(1).trim.drop("value:".length).trim
      Some(ResponseContent.Field(label = label, value = value, icon = icon))
    } else {
      val combined = body.mkString("\n").trim
      val idx = combined.indexOf(':')
      if (idx > 0) {
        val label = combined.take(idx).trim
        val value = combined.drop(idx + 1).trim
        Some(ResponseContent.Field(label = label, value = value, icon = icon))
      } else None
    }
  }

  private def blockFor(node: Node): Option[ResponseContent] = node match {
    case fc: FencedCodeBlock =>
      val lang = Option(fc.getInfo).map(_.trim).filter(_.nonEmpty)
      Some(ResponseContent.Code(fc.getLiteral.stripSuffix("\n"), lang))

    case _: ThematicBreak =>
      Some(ResponseContent.Divider)

    case p: Paragraph if isImageOnly(p) =>
      val img = p.getFirstChild.asInstanceOf[Image]
      URL.get(img.getDestination).toOption.map { url =>
        val alt = Option(textOf(img)).map(_.trim).filter(_.nonEmpty)
        ResponseContent.Image(url, alt)
      }

    case bl: BulletList =>
      Some(ResponseContent.ItemList(items = listItems(bl), ordered = false))

    case ol: OrderedList =>
      Some(ResponseContent.ItemList(items = listItems(ol), ordered = true))

    case _ =>
      val source = renderMarkdown(node)
      if (source.trim.isEmpty) None
      else Some(ResponseContent.Markdown(source))
  }

  private def isImageOnly(p: Paragraph): Boolean = {
    val first = p.getFirstChild
    first != null && first.isInstanceOf[Image] && first.getNext == null
  }

  private def listItems(list: Node): List[String] = {
    val items = List.newBuilder[String]
    var item = list.getFirstChild
    while (item != null) {
      items += renderMarkdown(item).trim
      item = item.getNext
    }
    items.result()
  }

  /**
   * Flattened text content of a node — used for Heading / Image alt
   * text where we want plain text rather than markdown source.
   */
  private def textOf(node: Node): String = {
    val sb = new StringBuilder
    val visitor = new AbstractVisitor {
      override def visit(text: CMText): Unit = sb.append(text.getLiteral)
    }
    node.accept(visitor)
    sb.toString
  }

  private def renderMarkdown(node: Node): String = {
    val sb = new StringBuilder
    appendMarkdown(node, sb)
    sb.toString
  }

  private def appendMarkdown(node: Node, sb: StringBuilder): Unit = {
    import org.commonmark.node.*
    node match {
      case _: Paragraph =>
        appendChildren(node, sb)
      case _: Document =>
        appendChildren(node, sb)
      case t: CMText =>
        sb.append(t.getLiteral)
      case _: SoftLineBreak =>
        sb.append('\n')
      case _: HardLineBreak =>
        sb.append("  \n")
      case e: Emphasis =>
        sb.append('*'); appendChildren(e, sb); sb.append('*')
      case s: StrongEmphasis =>
        sb.append("**"); appendChildren(s, sb); sb.append("**")
      case c: Code =>
        sb.append('`').append(c.getLiteral).append('`')
      case l: Link =>
        sb.append('[')
        appendChildren(l, sb)
        sb.append("](").append(l.getDestination).append(')')
      case i: Image =>
        sb.append("![")
        appendChildren(i, sb)
        sb.append("](").append(i.getDestination).append(')')
      case h: Heading =>
        sb.append("#" * h.getLevel).append(' ')
        appendChildren(h, sb)
      case bq: BlockQuote =>
        var child = bq.getFirstChild
        while (child != null) {
          val inner = renderMarkdown(child).split("\n").map(line => "> " + line).mkString("\n")
          sb.append(inner)
          if (child.getNext != null) sb.append("\n")
          child = child.getNext
        }
      case bl: BulletList =>
        var item = bl.getFirstChild
        while (item != null) {
          sb.append("- ").append(renderMarkdown(item).trim).append('\n')
          item = item.getNext
        }
      case ol: OrderedList =>
        var item = ol.getFirstChild
        var n = 1
        while (item != null) {
          sb.append(n).append(". ").append(renderMarkdown(item).trim).append('\n')
          item = item.getNext
          n += 1
        }
      case _ =>
        appendChildren(node, sb)
    }
  }

  private def appendChildren(node: Node, sb: StringBuilder): Unit = {
    var child = node.getFirstChild
    while (child != null) {
      appendMarkdown(child, sb)
      child = child.getNext
    }
  }
}
