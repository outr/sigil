package spec

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.tool.model.{MultipartParser, ResponseContent, SelectOption}

class OptionsParserSpec extends AnyWordSpec with Matchers {
  "MultipartParser ▶Options handling" should {
    "parse a single-select payload" in {
      val content =
        """▶Options
          |{"prompt":"Region","options":[{"label":"US East","value":"us-east"},{"label":"EU West","value":"eu-west"}]}
          |""".stripMargin
      MultipartParser.parse(content) should be(
        Vector(
          ResponseContent.Options(
            prompt = "Region",
            options = List(
              SelectOption("US East", "us-east"),
              SelectOption("EU West", "eu-west")
            )
          )
        )
      )
    }

    "parse a multi-select payload with allowMultiple=true" in {
      val content =
        """▶Options
          |{"prompt":"Channels","allowMultiple":true,"options":[{"label":"Email","value":"email"},{"label":"SMS","value":"sms"}]}
          |""".stripMargin
      val result = MultipartParser.parse(content)
      result should have size 1
      val opts = result.head.asInstanceOf[ResponseContent.Options]
      opts.allowMultiple should be(true)
      opts.options.map(_.value) should be(List("email", "sms"))
    }

    "carry exclusive=true on an escape-hatch option" in {
      val content =
        """▶Options
          |{"prompt":"Channels","allowMultiple":true,"options":[{"label":"Email","value":"email"},{"label":"SMS","value":"sms"},{"label":"None","value":"none","exclusive":true}]}
          |""".stripMargin
      val opts = MultipartParser.parse(content).head.asInstanceOf[ResponseContent.Options]
      opts.options.map(o => o.value -> o.exclusive) should be(List("email" -> false, "sms" -> false, "none" -> true))
    }

    "preserve option descriptions when provided" in {
      val content =
        """▶Options
          |{"prompt":"Plan","options":[{"label":"Pro","value":"pro","description":"Unlimited seats"},{"label":"Free","value":"free"}]}
          |""".stripMargin
      val opts = MultipartParser.parse(content).head.asInstanceOf[ResponseContent.Options]
      opts.options.head.description should be(Some("Unlimited seats"))
      opts.options(1).description should be(None)
    }

    "mix a ▶Text lead-in with an ▶Options block" in {
      val content =
        """▶Text
          |Which notification channels would you like?
          |▶Options
          |{"prompt":"Channels","allowMultiple":true,"options":[{"label":"Email","value":"email"}]}
          |""".stripMargin
      val blocks = MultipartParser.parse(content)
      blocks should have size 2
      blocks.head should be(ResponseContent.Text("Which notification channels would you like?"))
      blocks(1) shouldBe a[ResponseContent.Options]
    }

    "fall back to Text when the Options body isn't valid JSON" in {
      val content =
        """▶Options
          |this is not json at all
          |""".stripMargin
      MultipartParser.parse(content) should be(Vector(ResponseContent.Text("this is not json at all")))
    }

    "fall back to Text when the Options body is valid JSON but missing required fields" in {
      val content =
        """▶Options
          |{"not":"an options payload"}
          |""".stripMargin
      MultipartParser.parse(content) should be(Vector(ResponseContent.Text("""{"not":"an options payload"}""")))
    }
  }
}
