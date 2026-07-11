package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ContextFrame, Conversation, TurnInput}
import sigil.db.Model
import sigil.event.Message
import sigil.provider.{ConversationRequest, Effort, GenerationSettings, Instructions, Mode, ConversationMode, Provider, ProviderEvent, StopReason}
import sigil.tool.core.{ChangeModeTool, CoreTools, FindCapabilityInput, RespondTool}
import sigil.tool.{Tool, ToolInput}
import sigil.tool.model.{ChangeModeInput, RespondInput, RespondOptionsInput, ResponseContent}

trait AbstractProviderSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  // 5-min cap accommodates worst-case live-LLM latency under 4-way
  // fork contention; the framework's default 1-min routinely killed
  // healthy in-flight calls.
  override implicit protected val testTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.DurationInt(5).minutes

  TestSigil.initFor(getClass.getSimpleName)

  protected def provider: Task[Provider]

  protected def modelId: Id[Model]

  // Tools used in these specs. `CoreTools.all` is the default roster (no
  // `change_mode`, since it's opt-in for multi-mode apps); we add
  // ChangeModeTool here so the multi-mode "switch modes" assertion has
  // a real `change_mode` tool to surface to the model.
  // ChangeModeTool prepended (not appended) so it precedes `respond`
  // and the rest of CoreTools in the rendered roster — small / quantised
  // models have a real tool-position bias and pick the first relevant
  // tool they see. `Sigil.effectiveToolNames` puts change_mode at
  // priority 0 in production; the test fixture mirrors that ordering.
  protected def coreTools: Vector[Tool] = ChangeModeTool +: CoreTools.all

  protected def supportsThinking: Boolean = true

  protected def request(message: String,
                        currentMode: Mode = ConversationMode,
                        generationSettings: GenerationSettings =
                          GenerationSettings(maxOutputTokens = Some(1500), temperature = Some(0.0))): Task[List[ProviderEvent]] = provider.flatMap { p =>
    val conversationId = Conversation.id("test-conversation")
    val userMessage = Message(
      participantId = TestUser,
      conversationId = conversationId,
      topicId = TestTopicId,
      content = Vector(ResponseContent.Text(message))
    )
    val frames = Vector(ContextFrame.Text(
      content = message,
      participantId = TestUser,
      sourceEventId = userMessage._id
    ): ContextFrame)
    val request = ConversationRequest(
      conversationId = conversationId,
      model = TestSigil.testModel(modelId),
      instructions = Instructions(),
      turnInput = TurnInput(conversationId = conversationId, frames = frames),
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
        start.map(_.toolName) shouldBe Some(RespondTool.schema.name.value)

        val complete = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: RespondInput) => i }
        complete should not be empty
        complete.get.content should include("4")
        complete.get.topicLabel.trim should not be empty

        val usage = events.collectFirst { case u: ProviderEvent.Usage => u }
        usage should not be empty
        usage.get.usage.totalTokens should be > 0

        events.last shouldBe a[ProviderEvent.Done]
        events.last.asInstanceOf[ProviderEvent.Done].stopReason shouldBe StopReason.ToolCall
      }
    "emit a single-select Options block via respond_options when the user asks to be presented choices" in
      request(
        "I need to pick a backend language for a new web service. Ask me which of Python, Node.js, or Go I want."
      ).map { events =>
        val complete = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: RespondOptionsInput) => i }
        complete should not be empty
        complete.get.allowMultiple should be(false)
        complete.get.options.size should be(3)
      }
    "emit a multi-select Options block with an exclusive escape-hatch option via respond_options" in
      request(
        "I want to enable notifications. Ask me which of email, SMS, or push I want — multiple selections are allowed. Also include a None option that cannot be combined with the others."
      ).map { events =>
        val complete = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: RespondOptionsInput) => i }
        complete should not be empty
        complete.get.allowMultiple should be(true)
        complete.get.options.exists(_.exclusive) should be(true)
        complete.get.options.count(_.exclusive) should be(1)
      }
    "seek a capability when the user requests an action no core tool can perform" in
      request(
        "Post a quick update to my team's #engineering Slack channel: \"deploy finished successfully.\""
      ).map { events =>
        val start = events.collectFirst { case s: ProviderEvent.ToolCallStart => s }
        val toolName = start.map(_.toolName)
        // Both are valid capability-seeking moves for an out-of-roster
        // action: discover a tool for it, or switch to a mode that might
        // carry one — live models split between the two, and either
        // satisfies the discovery-first contract. What this pins against
        // is answering or refusing via the respond family without
        // seeking at all.
        toolName should (be(Some("find_capability")) or be(Some(ChangeModeTool.schema.name.value)))
        if (toolName.contains("find_capability")) {
          val input = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: FindCapabilityInput) => i }
          input should not be empty
          input.get.keywords should (include("slack") or include("post") or include("message"))
        } else {
          val input = events.collectFirst { case ProviderEvent.ToolCallComplete(_, i: ChangeModeInput) => i }
          input should not be empty
        }
      }
    "never mark an announce-shaped respond as turn-ending" in
      request(
        "Compile a list of every configuration file in my project and what each one controls."
      ).map { events =>
        // The model may seek a capability (ideal) or narrate a status
        // pulse — both are fine. What must never happen is the failure
        // shape this pins: a respond whose content ANNOUNCES work not
        // yet done ("Searching…", "I'll now…") arriving with
        // endsTurn = true, which settles the turn at iteration 1 with
        // zero work behind the announcement.
        val announceHead =
          "(?i)^\\s*(searching|scanning|looking|checking|starting|working on|let me (?:search|check|start|look|scan|compile)|i'?ll (?:now|start|begin|search|scan|compile)|i will (?:now|start|begin|search|scan|compile))".r
        val announcedTerminals = events.collect {
          case ProviderEvent.ToolCallComplete(_, i: RespondInput)
            if i.endsTurn && announceHead.findFirstIn(i.content).isDefined => i.content.take(120)
        }
        withClue(s"announce-shaped respond(s) ended the turn: $announcedTerminals: ") {
          announcedTerminals shouldBe empty
        }
      }
    "switch modes when the user's task belongs to a different mode" in
      request("I need to write a Scala function.").map { events =>
        val start = events.collectFirst { case s: ProviderEvent.ToolCallStart => s }
        start.map(_.toolName) shouldBe Some(ChangeModeTool.schema.name.value)

        val input = events.collectFirst {
          case ProviderEvent.ToolCallComplete(_, i: ChangeModeInput) => i
        }
        input.map(_.mode) shouldBe Some(TestCodingMode.name)

        events.last shouldBe a[ProviderEvent.Done]
        events.last.asInstanceOf[ProviderEvent.Done].stopReason shouldBe StopReason.ToolCall
      }
    if (supportsThinking) {
      "still round-trip a tool call when thinking is enabled" in {
        val gen = GenerationSettings(
          maxOutputTokens = Some(3000),
          temperature = Some(1.0),
          effort = Some(Effort.Low)
        )
        // Asserts thinking-mode still produces a respond-family tool call.
        val responseFamily = Set(RespondTool.schema.name.value, "respond_options")
        request("What is 2+2? Respond with just the number.", generationSettings = gen).map { events =>
          val start = events.collectFirst { case s: ProviderEvent.ToolCallStart => s }
          responseFamily should contain (start.map(_.toolName).getOrElse(""))
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
