package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{Conversation, Topic}
import sigil.event.Message
import sigil.signal.{ContentKind, EventState, MessageContentDelta, MessageDelta}
import sigil.tool.model.{ResponseContent, SelectOption}

/**
 * Regression for BUGS.md Sigil#5 — `MessageDelta.materialize` used to fall
 * back to `ResponseContent.Text(rawJsonBody)` for `ContentKind.Options` and
 * `ContentKind.Field`, leaking JSON into UIs that filter on Text. The fix
 * parses the JSON body to the structured variant; malformed JSON keeps the
 * Text fallback as a graceful degrade.
 */
class MessageDeltaSpec extends AnyWordSpec with Matchers {
  private val convId = Id[Conversation]("conv")
  private val topicId = Id[Topic]("topic")

  private def baseMessage: Message =
    Message(
      participantId = TestUser,
      conversationId = convId,
      topicId = topicId,
      state = EventState.Active
    )

  private def applyContentDelta(target: Message, kind: ContentKind, body: String): Message = {
    val delta = MessageDelta(
      target = target._id,
      conversationId = convId,
      content = Some(MessageContentDelta(kind = kind, complete = true, delta = body))
    )
    delta.apply(target).asInstanceOf[Message]
  }

  "MessageDelta.apply (materialize)" should {
    "parse a ContentKind.Options JSON body into a typed ResponseContent.Options" in {
      val body =
        """{"prompt":"What would you like to work on?","options":[{"label":"Write new code","value":"write_code"},{"label":"Review a PR","value":"review_pr"}]}"""
      val updated = applyContentDelta(baseMessage, ContentKind.Options, body)
      updated.content should have size 1
      updated.content.head shouldBe ResponseContent.Options(
        prompt = "What would you like to work on?",
        options = List(
          SelectOption(label = "Write new code", value = "write_code"),
          SelectOption(label = "Review a PR",   value = "review_pr")
        ),
        allowMultiple = false
      )
    }

    "parse a ContentKind.Options body honoring allowMultiple" in {
      val body =
        """{"prompt":"Pick any","options":[{"label":"A","value":"a"},{"label":"B","value":"b"}],"allowMultiple":true}"""
      val updated = applyContentDelta(baseMessage, ContentKind.Options, body)
      updated.content.head shouldBe a[ResponseContent.Options]
      val opts = updated.content.head.asInstanceOf[ResponseContent.Options]
      opts.allowMultiple shouldBe true
    }

    "parse a ContentKind.Field JSON body into a typed ResponseContent.Field" in {
      val body = """{"label":"Status","value":"Ready","icon":"check"}"""
      val updated = applyContentDelta(baseMessage, ContentKind.Field, body)
      updated.content.head shouldBe ResponseContent.Field(
        label = "Status",
        value = "Ready",
        icon = Some("check")
      )
    }

    "fall back to Text when an Options body is malformed JSON (graceful degrade)" in {
      val malformed = """{"prompt":"oops","options":[ broken """
      val updated = applyContentDelta(baseMessage, ContentKind.Options, malformed)
      updated.content.head shouldBe ResponseContent.Text(malformed)
    }

    "fall back to Text when a Field body is malformed JSON (graceful degrade)" in {
      val malformed = """{not even json"""
      val updated = applyContentDelta(baseMessage, ContentKind.Field, malformed)
      updated.content.head shouldBe ResponseContent.Text(malformed)
    }
  }
}
