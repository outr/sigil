package sigil.render

import sigil.tool.model.{Card, ResponseContent}

/**
 * Renders [[ResponseContent]] blocks as Slack mrkdwn — Slack's
 * markdown-adjacent dialect used in `text` payloads on regular
 * messages and inside `section.text` Block Kit elements.
 *
 * Differences from CommonMark Slack consumers care about:
 *   - Bold uses `*single asterisks*`, not `**`.
 *   - Italic uses `_underscores_`.
 *   - Strikethrough uses `~tildes~`.
 *   - Links are `<url|label>`, not `[label](url)`.
 *   - Headings collapse to bolded leading lines (Slack ignores `#`).
 *   - No tables — render as code block for monospaced alignment.
 *   - No images inline (Slack handles those as attachments / Block Kit
 *     image blocks); fall back to a labelled link.
 */
object SlackMrkdwnRenderer extends ContentRenderer[String] {
  override def empty: String = ""

  override def combine(a: String, b: String): String =
    if (a.isEmpty) b else if (b.isEmpty) a else s"$a\n\n$b"

  override def renderBlock(block: ResponseContent): String = block match {
    case ResponseContent.Text(text)             => text
    case ResponseContent.Markdown(text)         => convertMarkdownToMrkdwn(text)
    case ResponseContent.Heading(text)          => s"*$text*"
    case ResponseContent.Code(code, _)          => s"```\n$code\n```"
    case ResponseContent.Diff(diff, _)          => s"```\n$diff\n```"
    case ResponseContent.Table(headers, rows)   => s"```\n${renderTable(headers, rows)}\n```"
    case ResponseContent.ItemList(items, true)  => items.zipWithIndex.map { case (i, n) => s"${n + 1}. $i" }.mkString("\n")
    case ResponseContent.ItemList(items, false) => items.map(i => s"• $i").mkString("\n")
    case ResponseContent.Link(url, label)       => s"<$url|$label>"
    case ResponseContent.Image(url, alt)        => s"<$url|${alt.getOrElse("image")}>"
    case ResponseContent.Citation(src, exc, u) =>
      val link = u.fold(src)(u => s"<$u|$src>")
      exc.fold(s"_${link}_")(e => s"_${link}: ${e}_")
    case ResponseContent.Field(label, value, _) => s"*$label:* $value"
    case ResponseContent.Divider                => "──────────"
    case ResponseContent.Options(prompt, opts, _) =>
      val items = opts.map(o => s"• *${o.label}* — ${o.value}").mkString("\n")
      s"$prompt\n$items"
    case ResponseContent.Failure(reason, recoverable) =>
      val tag = if (recoverable) ":warning: Failure (recoverable)" else ":x: Failure"
      s"*$tag:* $reason"
    case ResponseContent.TextInput(label, _, placeholder, default) =>
      val hint = placeholder.orElse(default).fold("")(h => s" _($h)_")
      s"*$label:*$hint"
    case ResponseContent.SecretInput(label, _, _) => s"*$label:* _(secret)_"
    case ResponseContent.SecretRef(_, label)      => s"*$label:* ••••••••"
    case ResponseContent.StoredFileReference(_, title, _, _, size) =>
      s":paperclip: *$title* (${formatSize(size)})"
    case c: ResponseContent.Card => renderCard(c)
  }

  private def renderCard(card: ResponseContent.Card): String = {
    val titleLine = card.title.map(t => s"*$t*").getOrElse("")
    val body = render(Card.typedSections(card))
    val parts = List(titleLine, body).filter(_.nonEmpty)
    if (parts.isEmpty) "" else parts.mkString("\n")
  }

  private def renderTable(headers: List[String], rows: List[List[String]]): String = {
    val widths = (headers :: rows).transpose.map(_.map(_.length).maxOption.getOrElse(0))
    def fmt(row: List[String]): String =
      row.zip(widths).map { case (c, w) => c.padTo(w, ' ') }.mkString(" | ")
    val head = fmt(headers)
    val sep = widths.map(w => "-" * w).mkString("-+-")
    (head :: sep :: rows.map(fmt)).mkString("\n")
  }

  private def convertMarkdownToMrkdwn(md: String): String =
    md
      .replaceAll("""\*\*(.+?)\*\*""", "*$1*")
      .replaceAll("""\[([^\]]+)\]\(([^)]+)\)""", "<$2|$1>")

  private def formatSize(bytes: Long): String =
    if (bytes < 1024) s"$bytes B"
    else if (bytes < 1024 * 1024) f"${bytes / 1024.0}%.1f KB"
    else if (bytes < 1024L * 1024 * 1024) f"${bytes / (1024.0 * 1024)}%.1f MB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.1f GB"
}
