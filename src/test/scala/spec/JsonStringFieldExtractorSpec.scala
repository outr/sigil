package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.model.JsonStringFieldExtractor

class JsonStringFieldExtractorSpec extends AnyWordSpec with Matchers {
  private def feed(field: String, chunks: String*): String = {
    val ex = new JsonStringFieldExtractor(field)
    chunks.map(ex.append).mkString
  }

  "JsonStringFieldExtractor" should {
    "decode a simple value in one chunk" in {
      feed("content", """{"content":"hello"}""") shouldBe "hello"
    }

    "decode common escape sequences" in {
      feed("content", """{"content":"line1\nline2\t\"quoted\""}""") shouldBe "line1\nline2\t\"quoted\""
    }

    "decode \\uXXXX escapes" in {
      feed("content", """{"content":"\u25b6Text"}""") shouldBe "▶Text"
    }

    "tolerate whitespace around the key and colon" in {
      feed("content", """{ "content" : "hi" }""") shouldBe "hi"
    }

    "split correctly across chunk boundaries inside the prefix" in {
      feed("content", """{"con""", """tent":"hi"}""") shouldBe "hi"
    }

    "split correctly across chunk boundaries mid-escape" in {
      feed("content", """{"content":"a\""", """nb"}""") shouldBe "a\nb"
    }

    "ignore characters after the closing quote" in {
      feed("content", """{"content":"x"} extra junk""") shouldBe "x"
    }

    "decode incrementally one char at a time" in {
      val raw = """{"content":"▶Text\n4"}"""
      val ex = new JsonStringFieldExtractor("content")
      val sb = new StringBuilder
      raw.foreach(c => sb.append(ex.append(c.toString)))
      sb.toString shouldBe "▶Text\n4"
    }
  }
}
