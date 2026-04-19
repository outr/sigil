package spec

import fabric.rw.*
import lightdb.id.Id
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Message
import sigil.participant.ParticipantId
import sigil.provider.{GenerationSettings, Instructions, Mode, Provider, ProviderEvent, ProviderRequest, StopReason}
import sigil.tool.{ChangeModeTool, RespondTool, Tool, ToolInput}
import sigil.tool.model.{ChangeModeInput, RespondInput, ResponseContent}

trait AbstractProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  // Register core ToolInput subtypes so polymorphic serialization
  // (e.g., ProviderEvent.asString → input.json) works in tests.
  ToolInput.register(summon[RW[RespondInput]], summon[RW[ChangeModeInput]])

  protected def provider: Task[Provider]

  protected def modelId: Id[Model]

  protected def coreTools: Vector[Tool[? <: ToolInput]] = Vector(RespondTool, ChangeModeTool)

  protected def request(message: String,
                        currentMode: Mode = Mode.Conversation): Task[List[ProviderEvent]] = provider.flatMap { p =>
    val request = ProviderRequest(
      conversationId = Conversation.id("test-conversation"),
      modelId = modelId,
      instructions = Instructions(),
      events = Vector(
        Message(
          participantId = TestUser,
          content = Vector(ResponseContent.Text(message))
        )
      ),
      currentMode = currentMode,
      generationSettings = GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0)),
      tools = coreTools
    )
    p(request).toList
  }

  getClass.getSimpleName should {
    "properly list models" in {
      provider.map { p =>
        scribe.info(s"Found models: ${p.models.map(_.name).mkString(", ")}")
        p.models should not be empty
      }
    }
    "perform a round-trip request via the respond tool" in {
      request("What is 2+2? Respond with just the number.").map { events =>
        events.map(_.asString) should be(
          List(
            "ToolCallStart(respond)",
            """ToolCallComplete(RespondInput(Vector(Text(4)),None))""",
            "Done(ToolCall)"
          ))
      }
    }
    "switch modes when the user's task belongs to a different mode" in {
      request("I need to write a Scala function.").map { events =>
        val start = events.collectFirst { case s: ProviderEvent.ToolCallStart => s }
        start.map(_.toolName) shouldBe Some(ChangeModeTool.schema.name)

        val input = events.collectFirst {
          case ProviderEvent.ToolCallComplete(_, i: ChangeModeInput) => i
        }
        input.map(_.mode) shouldBe Some(Mode.Coding)

        events.last shouldBe a[ProviderEvent.Done]
        events.last.asInstanceOf[ProviderEvent.Done].stopReason shouldBe StopReason.ToolCall
      }
    }
  }

  implicit class EventsListExtras(events: List[ProviderEvent]) {
    def log(): Unit = scribe.info(s"Events: \n\t${events.map(_.asString).mkString("\n\t")}")
  }

  case object TestUser extends ParticipantId {
    override val value: String = "test-user"
  }
}
