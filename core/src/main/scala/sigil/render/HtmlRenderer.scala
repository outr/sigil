package sigil.render

import sigil.tool.model.{Card, ResponseContent}

/**
 * Renders [[ResponseContent]] blocks as HTML — suitable for email
 * bodies, web preview panes, and any consumer that accepts HTML.
 *
 * Output is a sequence of self-contained block-level elements
 * (`<p>`, `<h2>`, `<pre>`, `<table>`, `<hr/>`, …) joined with newlines.
 * Renderers that need to wrap the result in a full HTML document add
 * the `<html><body>` chrome themselves.
 *
 * All textual content is HTML-escaped via [[escape]]. Cards render as
 * `<section class="card">` blocks; the optional `kind` is exposed as a
 * `data-kind` attribute so receiving stylesheets can theme by category.
 */
object HtmlRenderer extends ContentRenderer[String] {
  override def empty: String = ""

  override def combine(a: String, b: String): String =
    if (a.isEmpty) b else if (b.isEmpty) a else s"$a\n$b"

  override def renderBlock(block: ResponseContent): String = block match {
    case ResponseContent.Text(text)             => s"<p>${escape(text)}</p>"
    case ResponseContent.Markdown(text)         => s"<p>${escape(text)}</p>"
    case ResponseContent.Heading(text)          => s"<h2>${escape(text)}</h2>"
    case ResponseContent.Code(code, lang) =>
      val cls = lang.fold("")(l => s""" class="language-${escape(l)}"""")
      s"<pre><code$cls>${escape(code)}</code></pre>"
    case ResponseContent.Diff(diff, _) =>
      s"""<pre><code class="language-diff">${escape(diff)}</code></pre>"""
    case ResponseContent.Table(headers, rows)   => renderTable(headers, rows)
    case ResponseContent.ItemList(items, true) =>
      "<ol>" + items.map(i => s"<li>${escape(i)}</li>").mkString + "</ol>"
    case ResponseContent.ItemList(items, false) =>
      "<ul>" + items.map(i => s"<li>${escape(i)}</li>").mkString + "</ul>"
    case ResponseContent.Link(url, label) =>
      s"""<a href="${escape(url.toString)}">${escape(label)}</a>"""
    case ResponseContent.Image(url, alt) =>
      s"""<img src="${escape(url.toString)}" alt="${escape(alt.getOrElse(""))}"/>"""
    case ResponseContent.Citation(src, exc, u) =>
      val link = u.fold(escape(src))(u => s"""<a href="${escape(u.toString)}">${escape(src)}</a>""")
      val excerpt = exc.fold("")(e => s": ${escape(e)}")
      s"<cite>$link$excerpt</cite>"
    case ResponseContent.Field(label, value, _) =>
      s"<p><strong>${escape(label)}:</strong> ${escape(value)}</p>"
    case ResponseContent.Divider                => "<hr/>"
    case ResponseContent.Options(prompt, opts, _) =>
      val items = opts
        .map(o => s"<li><strong>${escape(o.label)}</strong> — ${escape(o.value)}</li>")
        .mkString
      s"<p>${escape(prompt)}</p><ul>$items</ul>"
    case ResponseContent.Failure(reason, recoverable) =>
      val cls = if (recoverable) "failure recoverable" else "failure"
      s"""<p class="$cls"><strong>Failure:</strong> ${escape(reason)}</p>"""
    case ResponseContent.TextInput(label, id, placeholder, default) =>
      val ph = placeholder.fold("")(p => s""" placeholder="${escape(p)}"""")
      val dv = default.fold("")(d => s""" value="${escape(d)}"""")
      s"""<label for="${escape(id)}">${escape(label)}</label><input type="text" id="${escape(id)}"$ph$dv/>"""
    case ResponseContent.SecretInput(label, id, _) =>
      s"""<label for="${escape(id)}">${escape(label)}</label><input type="password" id="${escape(id)}"/>"""
    case ResponseContent.SecretRef(_, label) =>
      s"<p><strong>${escape(label)}:</strong> ••••••••</p>"
    case ResponseContent.StoredFileReference(_, title, _, _, size) =>
      s"""<p class="file">📎 <strong>${escape(title)}</strong> (${formatSize(size)})</p>"""
    case c: ResponseContent.Card => renderCard(c)
  }

  private def renderCard(card: ResponseContent.Card): String = {
    val kindAttr = card.kind.fold("")(k => s""" data-kind="${escape(k)}"""")
    val titleHtml = card.title.fold("")(t => s"<h3>${escape(t)}</h3>")
    val body = render(Card.typedSections(card))
    s"""<section class="card"$kindAttr>$titleHtml$body</section>"""
  }

  private def renderTable(headers: List[String], rows: List[List[String]]): String = {
    val head =
      if (headers.isEmpty) ""
      else "<thead><tr>" + headers.map(h => s"<th>${escape(h)}</th>").mkString + "</tr></thead>"
    val body =
      "<tbody>" +
        rows.map(r => "<tr>" + r.map(c => s"<td>${escape(c)}</td>").mkString + "</tr>").mkString +
        "</tbody>"
    s"<table>$head$body</table>"
  }

  private def escape(s: String): String =
    s.replace("&", "&amp;")
     .replace("<", "&lt;")
     .replace(">", "&gt;")
     .replace("\"", "&quot;")
     .replace("'", "&#39;")

  private def formatSize(bytes: Long): String =
    if (bytes < 1024) s"$bytes B"
    else if (bytes < 1024 * 1024) f"${bytes / 1024.0}%.1f KB"
    else if (bytes < 1024L * 1024 * 1024) f"${bytes / (1024.0 * 1024)}%.1f MB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.1f GB"
}
