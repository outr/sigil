package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextFrame, Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.provider.{ConversationRequest, Effort, GenerationSettings, Instructions, Mode, ConversationMode, Provider, ProviderEvent, StopReason}
import sigil.tool.core.{ChangeModeTool, CoreTools, FindCapabilityInput, RespondTool}
import sigil.tool.{Tool, ToolInput}
import sigil.tool.model.{ChangeModeInput, RespondInput, ResponseContent}

trait AbstractProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  protected def provider: Task[Provider]

  protected def modelId: Id[Model]

  protected def coreTools: Vector[Tool] = CoreTools.all

  protected def supportsThinking: Boolean = true

  protected def request(message: String,
                        currentMode: Mode = ConversationMode,
                        generationSettings: GenerationSettings =
                          GenerationSettings(maxOutputTokens = Some(200), temperature = Some(0.0))): Task[List[ProviderEvent]] = provider.flatMap { p =>
    val conversationId = Conversation.id("test-conversation")
    val userMessage = Message(
      participantId = TestUser,
      conversationId = conversationId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(message))
    )
    val view = ConversationView(
      conversationId = conversationId,
      frames = Vector(ContextFrame.Text(
        content = message,
        participantId = TestUser,
        sourceEventId =
          userMessage._id
      )),
      _id = ConversationView.idFor(conversationId)
    )
    val request = ConversationRequest(
      conversationId = conversationId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(view),
      currentMode = currentMode,
      currentTopic = TestTopicEntry,
      generationSettings = generationSettings,
      tools = coreTools,
      chain = List(TestUser, TestAgent)
    )
    p(request).toList
  }

  getClass.getSimpleName should {
    "perform a round-trip request via the respond tool" in
      request("What is 2+2? Respond with just the number.").map { events =>
        val start = events.collectFirst { case s: ProviderEvent.ToolCallStart => s }
        // ProviderEvent.ToolCallStart.toolName is a wire-level String
        // (before conversion to ToolName at the Orchestrator boundary).
        start.map(_.toolName) shouldBe Some(RespondTool.schema.name.value)

        val blockStart = events.collectFirst { case s: ProviderEvent.ContentBlockStart => s }
        blockStart.map(_.blockType) shouldBe Some("Text")

        val streamedText = events.collect { case ProviderEvent.ContentBlockDelta(_, t) => t }.mkString
        streamedText.trim should be("4")

        val complete = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: RespondInput) => i }
        complete.map(_.content.trim) shouldBe Some("▶Text\n4")

        val usage = events.collectFirst { case u: ProviderEvent.Usage => u }
        usage should not be empty
        usage.get.usage.totalTokens should be > 0

        events.last shouldBe a[ProviderEvent.Done]
        events.last.asInstanceOf[ProviderEvent.Done].stopReason shouldBe StopReason.ToolCall
      }
    "emit a single-select ▶Options block when the user asks to be presented choices" in
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
    "emit a multi-select ▶Options block with an exclusive escape-hatch option" in
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
    "call find_capability when the user requests an action no core tool can perform" in
      request(
        "Post a quick update to my team's #engineering Slack channel: \"deploy finished successfully.\""
      ).map { events =>
        val start = events.collectFirst { case s: ProviderEvent.ToolCallStart => s }
        start.map(_.toolName) shouldBe Some("find_capability")

        val input = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: FindCapabilityInput) => i }
        input should not be empty
        input.get.keywords should (include("slack") or include("post") or include("message"))
      }
    "switch modes when the user's task belongs to a different mode" in
      request("I need to write a Scala function.").map { events =>
        val start = events.collectFirst { case s: ProviderEvent.ToolCallStart => s }
        start.map(_.toolName) shouldBe Some(ChangeModeTool.schema.name.value)

        val input = events.collectFirst {
          case ProviderEvent.ToolCallComplete(_, i: ChangeModeInput) => i
        }
        // ChangeModeInput.mode is now the stable name string (the framework
        // resolves it to a Mode instance via Sigil.modeByName at execute time).
        input.map(_.mode) shouldBe Some(TestCodingMode.name)

        events.last shouldBe a[ProviderEvent.Done]
        events.last.asInstanceOf[ProviderEvent.Done].stopReason shouldBe StopReason.ToolCall
      }
    if (supportsThinking) {
      "still round-trip a tool call when thinking is enabled" in {
        // Anthropic requires temperature=1.0 with thinking; keep the
        // generation settings permissive for all three providers and
        // give the model enough budget to think *and* emit a call.
        val gen = GenerationSettings(
          maxOutputTokens = Some(3000),
          temperature = Some(1.0),
          effort = Some(Effort.Low)
        )
        request("What is 2+2? Respond with just the number.", generationSettings = gen).map { events =>
          val start = events.collectFirst { case s: ProviderEvent.ToolCallStart => s }
          start.map(_.toolName) shouldBe Some(RespondTool.schema.name.value)
          events.last shouldBe a[ProviderEvent.Done]
          events.last.asInstanceOf[ProviderEvent.Done].stopReason shouldBe StopReason.ToolCall
        }
      }
    }
  }

  implicit class EventsListExtras(events: List[ProviderEvent]) {
    def log(): Unit = scribe.info(s"Events: \n\t${events.map(_.asString).mkString("\n\t")}")
  }
}
