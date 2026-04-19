package spec

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
import sigil.tool.core.{ChangeModeTool, CoreTools, RespondTool}
import sigil.tool.{Tool, ToolInput}
import sigil.tool.model.{ChangeModeInput, RespondInput, ResponseContent}

trait AbstractProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  // Register core ToolInput subtypes so polymorphic serialization
  // (e.g., ProviderEvent.asString → input.json) works in tests.
  ToolInput.register(CoreTools.inputRWs*)

  protected def provider: Task[Provider]

  protected def modelId: Id[Model]

  protected def coreTools: Vector[Tool[? <: ToolInput]] = CoreTools.all

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
        val start = events.collectFirst { case s: ProviderEvent.ToolCallStart => s }
        start.map(_.toolName) shouldBe Some(RespondTool.schema.name)

        val blockStart = events.collectFirst { case s: ProviderEvent.ContentBlockStart => s }
        blockStart.map(_.blockType) shouldBe Some("Text")

        val streamedText = events.collect { case ProviderEvent.ContentBlockDelta(_, t) => t }.mkString
        streamedText.trim should be("4")

        val complete = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: RespondInput) => i }
        complete.map(_.content) shouldBe Some("▶Text\n4")

        val usage = events.collectFirst { case u: ProviderEvent.Usage => u }
        usage should not be empty
        usage.get.usage.totalTokens should be > 0

        events.last shouldBe a[ProviderEvent.Done]
        events.last.asInstanceOf[ProviderEvent.Done].stopReason shouldBe StopReason.ToolCall
      }
    }
    "emit a single-select ▶Options block when the user asks to be presented choices" in {
      request(
        "I need to pick a backend language for a new web service. Ask me which of Python, Node.js, or Go I want."
      ).map { events =>
        val complete = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: RespondInput) => i }
        complete should not be empty
        val blocks = sigil.tool.model.MultipartParser.parse(complete.get.content)
        val options = blocks.collectFirst { case o: ResponseContent.Options => o }
        options should not be empty
        options.get.allowMultiple should be(false)
        options.get.options.size should be(3)
      }
    }
    "emit a multi-select ▶Options block with an exclusive escape-hatch option" in {
      request(
        "I want to enable notifications. Ask me which of email, SMS, or push I want — multiple selections are allowed. Also include a None option that cannot be combined with the others."
      ).map { events =>
        val complete = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: RespondInput) => i }
        complete should not be empty
        val blocks = sigil.tool.model.MultipartParser.parse(complete.get.content)
        val options = blocks.collectFirst { case o: ResponseContent.Options => o }
        options should not be empty
        options.get.allowMultiple should be(true)
        options.get.options.exists(_.exclusive) should be(true)
        options.get.options.count(_.exclusive) should be(1)
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
