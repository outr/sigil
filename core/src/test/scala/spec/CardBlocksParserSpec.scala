package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.model.{MultipartParser, ResponseContent}

class CardBlocksParserSpec extends AnyWordSpec with Matchers {
  "MultipartParser card-shaped blocks" should {
    "parse ▶Heading into ResponseContent.Heading" in {
      val content =
        """▶Heading
          |Scala 4.0 Released
          |""".stripMargin
      MultipartParser.parse(content) should be(Vector(ResponseContent.Heading("Scala 4.0 Released")))
    }

    "parse ▶Field with all fields present" in {
      val content =
        """▶Field
          |{"label":"Source","value":"Scala Center","icon":"article"}
          |""".stripMargin
      MultipartParser.parse(content) should be(Vector(ResponseContent.Field("Source", "Scala Center", Some("article"))))
    }

    "parse ▶Field with icon omitted" in {
      val content =
        """▶Field
          |{"label":"Published","value":"2026-03-14"}
          |""".stripMargin
      MultipartParser.parse(content) should be(Vector(ResponseContent.Field("Published", "2026-03-14", None)))
    }

    "fall back to Text when ▶Field body isn't valid JSON" in {
      val content =
        """▶Field
          |not a payload
          |""".stripMargin
      MultipartParser.parse(content) should be(Vector(ResponseContent.Text("not a payload")))
    }

    "fall back to Text when ▶Field body is valid JSON but missing required keys" in {
      val content =
        """▶Field
          |{"label":"Source"}
          |""".stripMargin
      MultipartParser.parse(content) should be(Vector(ResponseContent.Text("""{"label":"Source"}""")))
    }

    "emit ▶Divider even with no body" in {
      val content =
        """▶Heading
          |Intro
          |▶Divider
          |▶Text
          |Body
          |""".stripMargin
      MultipartParser.parse(content) should be(
        Vector(
          ResponseContent.Heading("Intro"),
          ResponseContent.Divider,
          ResponseContent.Text("Body")
        )
      )
    }

    "compose a news-item sequence (Heading + Field + Field + Text)" in {
      val content =
        """▶Heading
          |Scala 4.0 Released
          |▶Field
          |{"label":"Source","value":"Scala Center","icon":"article"}
          |▶Field
          |{"label":"Published","value":"2026-03-14","icon":"clock"}
          |▶Text
          |Scala 4.0 brings refined macros.
          |""".stripMargin
      val blocks = MultipartParser.parse(content)
      blocks should have size 4
      blocks(0) should be(ResponseContent.Heading("Scala 4.0 Released"))
      blocks(1) should be(ResponseContent.Field("Source", "Scala Center", Some("article")))
      blocks(2) should be(ResponseContent.Field("Published", "2026-03-14", Some("clock")))
      blocks(3) should be(ResponseContent.Text("Scala 4.0 brings refined macros."))
    }
  }
}
