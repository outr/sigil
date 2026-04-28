package sigil.tool.model

import org.commonmark.node.{
  AbstractVisitor, BulletList, FencedCodeBlock, Heading, Image, Link, Node,
  OrderedList, Paragraph, Text => CMText, ThematicBreak
}
import org.commonmark.parser.Parser as CMParser
import org.commonmark.renderer.html.HtmlRenderer
import spice.net.URL

import scala.jdk.CollectionConverters.*

/**
 * Parses an assembled markdown string into a `Vector[ResponseContent]`.
 *
 * Replaces the legacy `▶<TYPE>\n` multipart format. Agents now emit plain
 * markdown as their assistant content stream; the framework parses it
 * here at turn-settle time to produce the typed block sequence the rest
 * of the framework persists and renders.
 *
 * Block-level mapping:
 *   - Fenced code blocks (```lang...```)     → `ResponseContent.Code(code, language?)`
 *   - Headings (`#`, `##`, …)                → `ResponseContent.Heading(text)`
 *   - Thematic breaks (`---`)                → `ResponseContent.Divider`
 *   - Image-only paragraph (`![alt](url)`)   → `ResponseContent.Image(url, altText?)`
 *   - Bullet / ordered lists                 → `ResponseContent.ItemList(items, ordered)`
 *   - Anything else                          → `ResponseContent.Markdown(rendered)`
 *
 * This intentionally does NOT split inline links / images out of mixed
 * prose — keep them inline in the surrounding `Markdown` block so the
 * UI's markdown renderer handles them in context.
 *
 * Tables (pipe syntax) are not yet handled — commonmark-java's table
 * extension lives in a separate artifact. If apps emit tables, they fall
 * through as Markdown blocks; renderers that understand markdown tables
 * still display correctly.
 */
object MarkdownContentParser {
  private val parser = CMParser.builder().build()

  def parse(markdown: String): Vector[ResponseContent] = {
    if (markdown.trim.isEmpty) return Vector.empty
    val doc = parser.parse(markdown)
    val out = Vector.newBuilder[ResponseContent]
    var node = doc.getFirstChild
    while (node != null) {
      blockFor(node).foreach(out += _)
      node = node.getNext
    }
    out.result()
  }

  private def blockFor(node: Node): Option[ResponseContent] = node match {
    case fc: FencedCodeBlock =>
      val lang = Option(fc.getInfo).map(_.trim).filter(_.nonEmpty)
      Some(ResponseContent.Code(fc.getLiteral.stripSuffix("\n"), lang))

    case h: Heading =>
      Some(ResponseContent.Heading(textOf(h)))

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
      // Render the block back to markdown source for a Markdown
      // ResponseContent. We use a small custom render rather than
      // pulling in commonmark's TextRenderer because we want the
      // markdown source preserved (links, emphasis, etc.), not the
      // text-only flattening the TextRenderer produces.
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

  /** Flattened text content of a node — used for Heading / Image alt
    * text where we want plain text rather than markdown source. */
  private def textOf(node: Node): String = {
    val sb = new StringBuilder
    val visitor = new AbstractVisitor {
      override def visit(text: CMText): Unit = sb.append(text.getLiteral)
    }
    node.accept(visitor)
    sb.toString
  }

  /** Render a node back to a markdown-ish source string. We use the
    * HtmlRenderer's source-preserving backend by walking the AST and
    * reconstructing markers for the inline elements we care about
    * (paragraph, emphasis, strong, code, link, image, soft/hard break).
    * Sufficient for round-tripping into ResponseContent.Markdown so the
    * UI's markdown renderer sees the same structure the LLM produced. */
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
      case _: BlockQuote =>
        sb.append("> ")
        appendChildren(node, sb)
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
        // Fallback: append child text content
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
