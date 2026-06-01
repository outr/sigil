package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.model.{MarkdownContentParser, ResponseContent}
import spice.net.URL

class MarkdownContentParserSpec extends AnyWordSpec with Matchers {

  "MarkdownContentParser" should {
    "parse a fenced code block with language" in {
      val md = "```scala\ndef factorial(n: Int): Int = if (n <= 1) 1 else n * factorial(n - 1)\n```"
      val out = MarkdownContentParser.parse(md)
      out should have size 1
      out.head shouldBe ResponseContent.Code(
        "def factorial(n: Int): Int = if (n <= 1) 1 else n * factorial(n - 1)",
        Some("scala")
      )
    }

    "parse a fenced code block with no language" in {
      val md = "```\nplain code\n```"
      val out = MarkdownContentParser.parse(md)
      out.head shouldBe ResponseContent.Code("plain code", None)
    }

    "parse a heading" in {
      val out = MarkdownContentParser.parse("# Section title")
      out.head shouldBe ResponseContent.Heading("Section title")
    }

    "parse a thematic break as Divider" in {
      val out = MarkdownContentParser.parse("---")
      out.head shouldBe ResponseContent.Divider
    }

    "parse an image-only paragraph as Image" in {
      val out = MarkdownContentParser.parse("![A diagram](https://example.com/diagram.png)")
      out should have size 1
      out.head shouldBe a[ResponseContent.Image]
      val img = out.head.asInstanceOf[ResponseContent.Image]
      img.url shouldBe URL.get("https://example.com/diagram.png").toOption.get
      img.altText shouldBe Some("A diagram")
    }

    "parse an image with no alt text" in {
      val out = MarkdownContentParser.parse("![](https://example.com/img.png)")
      val img = out.head.asInstanceOf[ResponseContent.Image]
      img.altText shouldBe None
    }

    "parse a bullet list as ItemList(ordered=false)" in {
      val md = "- one\n- two\n- three"
      val out = MarkdownContentParser.parse(md)
      out.head shouldBe ResponseContent.ItemList(List("one", "two", "three"), ordered = false)
    }

    "parse a numbered list as ItemList(ordered=true)" in {
      val md = "1. first\n2. second"
      val out = MarkdownContentParser.parse(md)
      out.head shouldBe ResponseContent.ItemList(List("first", "second"), ordered = true)
    }

    "parse a plain prose paragraph as Markdown" in {
      val out = MarkdownContentParser.parse("Rome was founded in 753 BCE.")
      out should have size 1
      out.head shouldBe a[ResponseContent.Markdown]
      out.head.asInstanceOf[ResponseContent.Markdown].text should include("Rome was founded")
    }

    "parse a multi-block reply (prose + code) into separate blocks" in {
      val md =
        """The factorial pattern in Scala uses recursion:
          |
          |```scala
          |def factorial(n: Int): Int = if (n <= 1) 1 else n * factorial(n - 1)
          |```""".stripMargin
      val out = MarkdownContentParser.parse(md)
      out should have size 2
      out(0) shouldBe a[ResponseContent.Markdown]
      out(1) shouldBe a[ResponseContent.Code]
      out(1).asInstanceOf[ResponseContent.Code].language shouldBe Some("scala")
    }

    "preserve inline emphasis in markdown blocks" in {
      val out = MarkdownContentParser.parse("This is **bold** and *italic*.")
      out.head.asInstanceOf[ResponseContent.Markdown].text should (include("**bold**") and include("*italic*"))
    }

    "preserve inline links in markdown blocks" in {
      val out = MarkdownContentParser.parse("See [the docs](https://example.com/docs) for details.")
      out.head.asInstanceOf[ResponseContent.Markdown].text should include("[the docs](https://example.com/docs)")
    }

    // Sigil #346 — the respond_field fold relies on `respond`'s markdown
    // parsing these Field-callout forms. localhost:8081 (Qwen3.6) emitted
    // all three; the parser must recover a Field block from each or the
    // fold silently drops fields.
    "parse the two-line [!Field] callout" in {
      val out = MarkdownContentParser.parse("> [!Field icon=\"check\"]\n> Status: PASSED")
      out.collectFirst { case f: ResponseContent.Field => f } shouldBe
        Some(ResponseContent.Field(label = "Status", value = "PASSED", icon = Some("check")))
    }

    "parse the inline [!Field] callout (label/value on the directive line)" in {
      val out = MarkdownContentParser.parse("> [!Field icon=\"check\"] Status: PASSED")
      out.collectFirst { case f: ResponseContent.Field => f } shouldBe
        Some(ResponseContent.Field(label = "Status", value = "PASSED", icon = Some("check")))
    }

    "parse multiple [!Field] callouts packed in one blockquote" in {
      val md = "> [!Field icon=\"check\"]\n> Status: PASSED\n> [!Field icon=\"commit\"]\n> Commit: a1b2c3d"
      val fields = MarkdownContentParser.parse(md).collect { case f: ResponseContent.Field => f }
      fields shouldBe Vector(
        ResponseContent.Field(label = "Status", value = "PASSED", icon = Some("check")),
        ResponseContent.Field(label = "Commit", value = "a1b2c3d", icon = Some("commit"))
      )
    }

    "fall through to a plain blockquote when the alert type is unknown" in {
      val out = MarkdownContentParser.parse("> [!Note] just a note")
      out.collectFirst { case f: ResponseContent.Field => f } shouldBe None
      out should not be empty
    }

    "return empty vector for empty input" in {
      MarkdownContentParser.parse("")     should be(empty)
      MarkdownContentParser.parse("   ")  should be(empty)
      MarkdownContentParser.parse("\n\n") should be(empty)
    }
  }
}
