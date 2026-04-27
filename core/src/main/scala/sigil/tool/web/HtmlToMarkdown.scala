package sigil.tool.web

/**
 * Convert HTML into a markdown-ish rendering — heuristic, regex-
 * based, lossy. Used by [[WebFetchTool]] to make HTML responses
 * legible to LLMs without pulling a full HTML parser dep.
 *
 * Coverage: headings, paragraphs, links, bold/italic, inline code,
 * `<pre>` blocks, list items, line breaks; common HTML entities;
 * collapses excessive whitespace.
 */
object HtmlToMarkdown {
  def convert(html: String): String = {
    if (html.isEmpty) ""
    else {
      var result = html

      result = result.replaceAll("(?si)<pre[^>]*>(.*?)</pre>", "\n```\n$1\n```\n")

      result = result.replaceAll("(?i)<h1[^>]*>(.*?)</h1>", "\n# $1\n")
      result = result.replaceAll("(?i)<h2[^>]*>(.*?)</h2>", "\n## $1\n")
      result = result.replaceAll("(?i)<h3[^>]*>(.*?)</h3>", "\n### $1\n")
      result = result.replaceAll("(?i)<h4[^>]*>(.*?)</h4>", "\n#### $1\n")
      result = result.replaceAll("(?i)<h5[^>]*>(.*?)</h5>", "\n##### $1\n")
      result = result.replaceAll("(?i)<h6[^>]*>(.*?)</h6>", "\n###### $1\n")

      result = result.replaceAll("(?i)<p[^>]*>", "\n\n")
      result = result.replaceAll("(?i)</p>", "\n\n")

      result = result.replaceAll("""(?i)<a[^>]*href=["']([^"']*)["'][^>]*>(.*?)</a>""", "[$2]($1)")
      result = result.replaceAll("(?i)<(?:strong|b)>(.*?)</(?:strong|b)>", "**$1**")
      result = result.replaceAll("(?i)<(?:em|i)>(.*?)</(?:em|i)>", "*$1*")
      result = result.replaceAll("(?i)<code>(.*?)</code>", "`$1`")
      result = result.replaceAll("(?i)<li[^>]*>(.*?)</li>", "\n- $1")
      result = result.replaceAll("(?i)<br\\s*/?>", "\n")

      result = result.replaceAll("<[^>]+>", "")

      result = result
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

      result = result.replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
      result.trim
    }
  }
}
