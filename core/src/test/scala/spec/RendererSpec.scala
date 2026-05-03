package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.render.{ContentRenderer, HtmlRenderer, MarkdownRenderer, PlainTextRenderer, SlackMrkdwnRenderer}
import sigil.tool.model.{Card, ResponseContent, SelectOption}
import spice.net.URL

/**
 * Verifies that each shipped [[ContentRenderer]] handles every
 * [[ResponseContent]] variant — including the recursive `Card`. The
 * tests assert characteristic markup (markdown bold `**`, slack bold
 * `*`, html tags, plain-text uppercased headings) rather than full
 * string equality so renderers can be tweaked without churn here.
 */
class RendererSpec extends AnyWordSpec with Matchers {

  private val every: Vector[ResponseContent] = Vector(
    ResponseContent.Text("plain"),
    ResponseContent.Markdown("**markdown**"),
    ResponseContent.Heading("Heading One"),
    ResponseContent.Code("val x = 1", Some("scala")),
    ResponseContent.Diff("- a\n+ b", Some("file.scala")),
    ResponseContent.Table(List("k", "v"), List(List("foo", "1"), List("bar", "2"))),
    ResponseContent.ItemList(List("alpha", "beta"), ordered = false),
    ResponseContent.ItemList(List("first", "second"), ordered = true),
    ResponseContent.Link(URL.parse("https://example.com"), "example"),
    ResponseContent.Image(URL.parse("https://example.com/img.png"), Some("alt")),
    ResponseContent.Citation("Source A", Some("excerpt"), Some(URL.parse("https://example.com"))),
    ResponseContent.Field("Status", "Ready", Some("check")),
    ResponseContent.Divider,
    ResponseContent.Options(
      "Pick:",
      List(SelectOption("Yes", "yes"), SelectOption("No", "no"))
    ),
    ResponseContent.Failure("transient", recoverable = true),
    ResponseContent.TextInput("Name", "name-id", Some("placeholder"), None),
    ResponseContent.SecretInput("Token", "secret-id", sigil.security.SecretKind.Encrypted),
    ResponseContent.SecretRef("secret-id", "Token"),
    ResponseContent.StoredFileReference(
      lightdb.id.Id[sigil.storage.StoredFile]("f"),
      "report.pdf",
      None,
      "application/pdf",
      2048
    )
  )

  private val nestedCard: ResponseContent.Card = Card(
    sections = Vector(
      ResponseContent.Heading("Outer"),
      Card(
        sections = Vector(ResponseContent.Field("inner", "yes")),
        title = Some("Inner Card"),
        kind = Some("info")
      )
    ),
    title = Some("Outer Card"),
    kind = Some("metric")
  )

  private def renderAll(r: ContentRenderer[String]): String =
    r.render(every) + "\n\n" + r.renderBlock(nestedCard)

  "MarkdownRenderer" should {
    "render every variant including nested cards" in {
      val out = renderAll(MarkdownRenderer)
      out should include("## Heading One")
      out should include("```scala")
      out should include("**Status:** Ready")
      out should include("- alpha")
      out should include("1. first")
      out should include("[example](https://example.com)")
      out should include("![alt](https://example.com/img.png)")
      out should include("---")
      out should include("### Outer Card")
      out should include("### Inner Card")
      out should include("**inner:** yes")
    }
  }

  "SlackMrkdwnRenderer" should {
    "render every variant with Slack-flavoured markup" in {
      val out = renderAll(SlackMrkdwnRenderer)
      out should include("*Heading One*")
      out should include("*Status:* Ready")
      out should include("• alpha")
      out should include("<https://example.com|example>")
      out should not include "[example]"
      out should include("*Outer Card*")
      out should include("*Inner Card*")
    }

    "convert markdown bold and links to mrkdwn dialect" in {
      val out = SlackMrkdwnRenderer.renderBlock(ResponseContent.Markdown("**bold** and [link](https://x)"))
      out should include("*bold*")
      out should include("<https://x|link>")
    }
  }

  "HtmlRenderer" should {
    "render every variant as valid HTML and escape special chars" in {
      val out = renderAll(HtmlRenderer)
      out should include("<h2>Heading One</h2>")
      out should include("<pre><code class=\"language-scala\">")
      out should include("<table>")
      out should include("<ul>")
      out should include("<ol>")
      out should include("<a href=\"https://example.com\">example</a>")
      out should include("<img src=\"https://example.com/img.png\" alt=\"alt\"/>")
      out should include("<hr/>")
      out should include("""<section class="card" data-kind="metric">""")
      out should include("""<section class="card" data-kind="info">""")
      out should include("<h3>Outer Card</h3>")
    }

    "escape HTML special characters in text" in {
      val out = HtmlRenderer.renderBlock(ResponseContent.Text("<script>&\"'"))
      out shouldBe "<p>&lt;script&gt;&amp;&quot;&#39;</p>"
    }
  }

  "PlainTextRenderer" should {
    "render every variant without HTML or own-rendered markup" in {
      val out = renderAll(PlainTextRenderer)
      out should include("HEADING ONE")
      out should include("Status: Ready")
      out should include("- alpha")
      out should include("1. first")
      out should include("example (https://example.com)")
      out should include("Outer Card")
      out should include("Inner Card")
      // PlainText doesn't add its own bold/italic — Markdown blocks
      // pass through unchanged (stripping markdown isn't its job).
      out should not include "<h2>"
      out should not include "<table>"
    }

    "indent nested card sections relative to parent" in {
      val out = PlainTextRenderer.renderBlock(nestedCard)
      out.linesIterator.exists(_.startsWith("  ")) shouldBe true
    }
  }
}
