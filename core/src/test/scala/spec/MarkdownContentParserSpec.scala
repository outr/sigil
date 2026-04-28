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

    "return empty vector for empty input" in {
      MarkdownContentParser.parse("")     should be(empty)
      MarkdownContentParser.parse("   ")  should be(empty)
      MarkdownContentParser.parse("\n\n") should be(empty)
    }
  }
}
