package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, Instructions, Mode, Provider, ProviderEvent, ProviderRequest, StopReason}
import sigil.tool.RespondTool
import sigil.tool.model.ResponseContent

trait AbstractProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  protected def provider: Task[Provider]

  protected def modelId: Id[Model]

  getClass.getSimpleName should {
    "properly list models" in {
      provider.map { p =>
        scribe.info(s"Found models: ${p.models.map(_.name).mkString(", ")}")
        p.models should not be empty
      }
    }

    "perform a round-trip request via the respond tool" in {
      provider.flatMap { p =>
        val request = ProviderRequest(
          conversationId = Conversation.id("test-conversation"),
          modelId = modelId,
          instructions = Instructions(
            system = "You are a helpful assistant. Answer very briefly.",
            developer = None
          ),
          events = Vector(
            Message(
              participantId = TestUser,
              content = Vector(ResponseContent.Text("What is 2+2? Respond with just the number."))
            )
          ),
          currentMode = Mode.Conversation,
          generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0)),
          tools = Vector(RespondTool)
        )
        p(request).toList.map { events =>
          events.map(_.asString) should be(List(
            "ToolCallStart(respond)",
            """ToolCallComplete({"content":[{"type":"Text","text":"4"}]})""",
            "Done(ToolCall)"
          ))
        }
      }
    }
  }

  case object TestUser extends ParticipantId {
    override val value: String = "test-user"
  }
}
