package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.model.{MultipartStreamParser, ToolStreamEvent}

class MultipartStreamParserSpec extends AnyWordSpec with Matchers {
  import ToolStreamEvent.*

  private def feed(chunks: String*): Vector[ToolStreamEvent] = {
    val p = new MultipartStreamParser
    val out = Vector.newBuilder[ToolStreamEvent]
    chunks.foreach(c => out ++= p.append(c))
    out ++= p.finish()
    out.result()
  }

  "MultipartStreamParser" should {
    "parse a single Text block delivered in one chunk" in {
      feed("▶Text\n4\n") should be(Vector(BlockStart("Text", None), BlockDelta("4")))
    }

    "drop the trailing newline of the body before a header" in {
      val events = feed("▶Text\nHello\n▶Code python\nfoo\n")
      events should be(
        Vector(
          BlockStart("Text", None),
          BlockDelta("Hello"),
          BlockStart("Code", Some("python")),
          BlockDelta("foo")
        ))
    }

    "preserve internal newlines in body" in {
      val events = feed("▶Text\nline one\nline two\nline three\n")
      events should be(Vector(BlockStart("Text", None), BlockDelta("line one\nline two\nline three")))
    }

    "treat a non-matching ▶ line as body content" in {
      val events = feed("▶Text\n▶ pointing arrow\nrest\n")
      events should be(Vector(BlockStart("Text", None), BlockDelta("▶ pointing arrow\nrest")))
    }

    "handle headers split across chunk boundaries" in {
      val events = feed("▶Te", "xt\nHel", "lo\n")
      events should be(Vector(BlockStart("Text", None), BlockDelta("Hel"), BlockDelta("lo")))
    }

    "handle the body→header newline split across chunk boundaries" in {
      val events = feed("▶Text\nfoo", "\n▶Code scala\nbar\n")
      events should be(
        Vector(
          BlockStart("Text", None),
          BlockDelta("foo"),
          BlockStart("Code", Some("scala")),
          BlockDelta("bar")
        ))
    }

    "ignore content before the first header" in {
      val events = feed("garbage\n▶Text\nok\n")
      events should be(Vector(BlockStart("Text", None), BlockDelta("ok")))
    }
  }
}
