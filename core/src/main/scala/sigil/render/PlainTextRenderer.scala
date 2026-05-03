package sigil.render

import sigil.tool.model.{Card, ResponseContent}

/**
 * Renders [[ResponseContent]] blocks as unstyled plain text — for SMS,
 * voice TTS, log lines, accessibility fallbacks, or any consumer that
 * cannot render markup of any kind.
 *
 * Headings, fields, options and tables degrade to readable text:
 * headings are uppercased on their own line, fields render as
 * `Label: value`, tables use space-padded columns. Cards prefix the
 * title (if any) and indent body sections by two spaces so nested
 * cards stay visually grouped.
 */
object PlainTextRenderer extends ContentRenderer[String] {
  override def empty: String = ""

  override def combine(a: String, b: String): String =
    if (a.isEmpty) b else if (b.isEmpty) a else s"$a\n\n$b"

  override def renderBlock(block: ResponseContent): String = block match {
    case ResponseContent.Text(text)             => text
    case ResponseContent.Markdown(text)         => text
    case ResponseContent.Heading(text)          => text.toUpperCase
    case ResponseContent.Code(code, _)          => code
    case ResponseContent.Diff(diff, _)          => diff
    case ResponseContent.Table(headers, rows)   => renderTable(headers, rows)
    case ResponseContent.ItemList(items, true)  => items.zipWithIndex.map { case (i, n) => s"${n + 1}. $i" }.mkString("\n")
    case ResponseContent.ItemList(items, false) => items.map(i => s"- $i").mkString("\n")
    case ResponseContent.Link(url, label)       => s"$label ($url)"
    case ResponseContent.Image(url, alt)        => alt.fold(url.toString)(a => s"$a ($url)")
    case ResponseContent.Citation(src, exc, u) =>
      val tail = exc.fold("")(e => s": $e")
      val source = u.fold(src)(u => s"$src ($u)")
      s"$source$tail"
    case ResponseContent.Field(label, value, _) => s"$label: $value"
    case ResponseContent.Divider                => "---"
    case ResponseContent.Options(prompt, opts, _) =>
      val items = opts.map(o => s"- ${o.label}: ${o.value}").mkString("\n")
      s"$prompt\n$items"
    case ResponseContent.Failure(reason, recoverable) =>
      val tag = if (recoverable) "Failure (recoverable)" else "Failure"
      s"$tag: $reason"
    case ResponseContent.TextInput(label, _, placeholder, default) =>
      val hint = placeholder.orElse(default).fold("")(h => s" ($h)")
      s"$label:$hint"
    case ResponseContent.SecretInput(label, _, _) => s"$label: (secret)"
    case ResponseContent.SecretRef(_, label)      => s"$label: ••••••••"
    case ResponseContent.StoredFileReference(_, title, _, _, size) =>
      s"$title (${formatSize(size)})"
    case c: ResponseContent.Card => renderCard(c)
  }

  private def renderCard(card: ResponseContent.Card): String = {
    val titleLine = card.title.getOrElse("")
    val body = render(Card.typedSections(card))
    val indented = body.linesIterator.map(l => if (l.isEmpty) l else s"  $l").mkString("\n")
    val parts = List(titleLine, indented).filter(_.nonEmpty)
    if (parts.isEmpty) "" else parts.mkString("\n")
  }

  private def renderTable(headers: List[String], rows: List[List[String]]): String = {
    if (headers.isEmpty && rows.isEmpty) ""
    else {
      val widths = (headers :: rows).transpose.map(_.map(_.length).maxOption.getOrElse(0))
      def fmt(row: List[String]): String =
        row.zip(widths).map { case (c, w) => c.padTo(w, ' ') }.mkString(" | ")
      val head = fmt(headers)
      val sep = widths.map(w => "-" * w).mkString("-+-")
      (head :: sep :: rows.map(fmt)).mkString("\n")
    }
  }

  private def formatSize(bytes: Long): String =
    if (bytes < 1024) s"$bytes B"
    else if (bytes < 1024 * 1024) f"${bytes / 1024.0}%.1f KB"
    else if (bytes < 1024L * 1024 * 1024) f"${bytes / (1024.0 * 1024)}%.1f MB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.1f GB"
}
