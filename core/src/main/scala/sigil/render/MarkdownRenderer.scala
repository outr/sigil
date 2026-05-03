package sigil.render

import sigil.tool.model.{Card, ResponseContent}

/**
 * Renders [[ResponseContent]] blocks as CommonMark-compatible markdown.
 * The default for the in-app conversation UI and for any consumer that
 * accepts markdown text.
 *
 * Conventions:
 *   - Headings render as `## text` (level 2 — leaves H1 free for app-
 *     level page chrome).
 *   - Code uses fenced blocks with the language hint.
 *   - Cards render as a horizontal-rule-delimited section with the
 *     title (if any) as a `### ` subheading; nested cards recurse.
 *   - Block-Kit-only constructs (Options, TextInput, SecretInput,
 *     SecretRef, StoredFileReference) degrade to a readable text
 *     summary so the renderer stays useful in markdown-only contexts.
 */
object MarkdownRenderer extends ContentRenderer[String] {
  override def empty: String = ""

  override def combine(a: String, b: String): String =
    if (a.isEmpty) b else if (b.isEmpty) a else s"$a\n\n$b"

  override def renderBlock(block: ResponseContent): String = block match {
    case ResponseContent.Text(text)             => text
    case ResponseContent.Markdown(text)         => text
    case ResponseContent.Heading(text)          => s"## $text"
    case ResponseContent.Code(code, lang)       => s"```${lang.getOrElse("")}\n$code\n```"
    case ResponseContent.Diff(diff, _)          => s"```diff\n$diff\n```"
    case ResponseContent.Table(headers, rows)   => renderTable(headers, rows)
    case ResponseContent.ItemList(items, true)  => items.zipWithIndex.map { case (i, n) => s"${n + 1}. $i" }.mkString("\n")
    case ResponseContent.ItemList(items, false) => items.map(i => s"- $i").mkString("\n")
    case ResponseContent.Link(url, label)       => s"[$label]($url)"
    case ResponseContent.Image(url, alt)        => s"![${alt.getOrElse("")}]($url)"
    case ResponseContent.Citation(src, exc, u) =>
      val excerpt = exc.fold("")(e => s": $e")
      val link = u.fold(src)(u => s"[$src]($u)")
      s"_${link}${excerpt}_"
    case ResponseContent.Field(label, value, _) => s"**$label:** $value"
    case ResponseContent.Divider                => "---"
    case ResponseContent.Options(prompt, opts, _) =>
      val items = opts.map(o => s"- **${o.label}** — ${o.value}").mkString("\n")
      s"$prompt\n\n$items"
    case ResponseContent.Failure(reason, recoverable) =>
      val tag = if (recoverable) "Failure (recoverable)" else "Failure"
      s"**$tag:** $reason"
    case ResponseContent.TextInput(label, _, placeholder, default) =>
      val hint = placeholder.orElse(default).fold("")(h => s" _($h)_")
      s"**$label:**$hint"
    case ResponseContent.SecretInput(label, _, _) => s"**$label:** _(secret)_"
    case ResponseContent.SecretRef(_, label)      => s"**$label:** ••••••••"
    case ResponseContent.StoredFileReference(_, title, _, _, size) =>
      s"📎 **$title** (${formatSize(size)})"
    case c: ResponseContent.Card => renderCard(c)
  }

  private def renderCard(card: ResponseContent.Card): String = {
    val titleLine = card.title.map(t => s"### $t").getOrElse("")
    val body = render(Card.typedSections(card))
    val parts = List(titleLine, body).filter(_.nonEmpty)
    if (parts.isEmpty) "" else parts.mkString("\n\n")
  }

  private def renderTable(headers: List[String], rows: List[List[String]]): String = {
    if (headers.isEmpty && rows.isEmpty) ""
    else {
      val head = s"| ${headers.mkString(" | ")} |"
      val sep = s"| ${headers.map(_ => "---").mkString(" | ")} |"
      val body = rows.map(r => s"| ${r.mkString(" | ")} |").mkString("\n")
      List(head, sep, body).filter(_.nonEmpty).mkString("\n")
    }
  }

  private def formatSize(bytes: Long): String =
    if (bytes < 1024) s"$bytes B"
    else if (bytes < 1024 * 1024) f"${bytes / 1024.0}%.1f KB"
    else if (bytes < 1024L * 1024 * 1024) f"${bytes / (1024.0 * 1024)}%.1f MB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.1f GB"
}
