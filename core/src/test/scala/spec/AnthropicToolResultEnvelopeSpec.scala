package spec

import fabric.*
import lightdb.id.Id
import lightdb.time.Timestamp
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.{ContextFrame, FrameBuilder, ToolCallState, TurnInput}
import sigil.db.Model
import sigil.event.{Event, ToolInvoke, ToolOutcome}
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderRequest}
import sigil.provider.anthropic.AnthropicProvider
import sigil.signal.EventState
import sigil.tool.{ImageToolOutput, TextToolOutput, ToolName}
import sigil.tool.core.CoreTools
import spice.net.URL

/**
 * Sigil #305 — `tool_result.content` must render the inner string of
 * a [[TextToolOutput]] directly, not a JSON-stringified `{"text": "…"}`
 * envelope. Multimodal returns (e.g. [[ImageToolOutput]]) keep the
 * follow-up `user` image-message shape unchanged; that path was never
 * affected by the envelope bug because its rendered text channel
 * already flowed through a dedicated branch in
 * [[FrameBuilder.toolInvokePayload]].
 */
class AnthropicToolResultEnvelopeSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  private val conversationId = sigil.conversation.Conversation.id("tool-result-envelope")
  private val topicId = sigil.conversation.Topic.id("tool-result-envelope-topic")
  private val callId: Id[Event] = Id[Event]("call-text-tool-1")

  private def settledTextInvoke(text: String): ToolInvoke = ToolInvoke(
    toolName = ToolName("send_slack_message"),
    participantId = TestAgent,
    conversationId = conversationId,
    topicId = topicId,
    state = EventState.Complete,
    outcome = ToolOutcome.Success,
    output = TextToolOutput(text),
    timestamp = Timestamp(),
    _id = callId
  )

  private def settledImageInvoke(image: ImageToolOutput): ToolInvoke = ToolInvoke(
    toolName = ToolName("preview_theme"),
    participantId = TestAgent,
    conversationId = conversationId,
    topicId = topicId,
    state = EventState.Complete,
    outcome = ToolOutcome.Success,
    output = image,
    timestamp = Timestamp(),
    _id = callId
  )

  private def turnInputFor(invoke: ToolInvoke): TurnInput = {
    val toolFrame = FrameBuilder.computeFrame(invoke).get
    TurnInput(
      conversationId = conversationId,
      frames = Vector(
        ContextFrame.Text("kick off the tool", TestUser, Id[Event]("seed-u")),
        toolFrame,
        ContextFrame.Text("anything else?", TestUser, Id[Event]("seed-u2"))
      )
    )
  }

  private def requestFor(input: TurnInput): ProviderRequest = ConversationRequest(
    conversationId = conversationId,
    model = TestSigil.testModel(Model.id("anthropic", "claude-haiku-4-5")),
    instructions = Instructions(),
    turnInput = input,
    currentMode = ConversationMode,
    currentTopic = TestTopicEntry,
    generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
    tools = CoreTools.all,
    chain = List(TestUser, TestAgent)
  )

  private def render(provider: AnthropicProvider, input: TurnInput): Json = {
    val httpReq = provider.requestConverter(requestFor(input)).sync()
    httpReq.content match {
      case Some(c: spice.http.content.StringContent) => fabric.io.JsonParser(c.value)
      case _ => obj()
    }
  }

  private def toolResultBlocks(body: Json): Vector[Json] =
    body("messages").asVector.flatMap { msg =>
      msg("content").asVector.collect {
        case b if b.get("type").map(_.asString).contains("tool_result") => b
      }
    }

  "AnthropicProvider tool_result content (text-only)" should {
    val provider = AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil)

    "render TextToolOutput as a plain string, not a JSON-stringified envelope" in {
      val body = render(provider, turnInputFor(settledTextInvoke("Sent to #foo: hello")))
      val blocks = toolResultBlocks(body)
      blocks should have size 1
      val content = blocks.head("content")
      content shouldBe a[Str]
      content.asString shouldBe "Sent to #foo: hello"
      // Defence in depth — the envelope shape this bug fixed:
      content.asString should not include "{\"text\":"
    }

    "render an empty TextToolOutput as the empty string, not `{\"text\":\"\"}`" in {
      val body = render(provider, turnInputFor(settledTextInvoke("")))
      val blocks = toolResultBlocks(body)
      blocks should have size 1
      blocks.head("content") shouldBe a[Str]
      blocks.head("content").asString shouldBe ""
    }
  }

  "AnthropicProvider tool_result content (multimodal)" should {
    val provider = AnthropicProvider(apiKey = "sk-ant-test", sigilRef = TestSigil)
    val sampleUrl: URL =
      URL.get("sigil://storage/abc123", tldValidation = spice.net.TLDValidation.Off).toOption.get

    "render the textual channel of an ImageToolOutput as the caption string, " +
      "and surface the image as a follow-up user message" in {
        val body = render(
          provider,
          turnInputFor(settledImageInvoke(
            ImageToolOutput(sampleUrl, alt = "preview", text = Some("Preview of /pages/x"))
          )))
        val blocks = toolResultBlocks(body)
        blocks should have size 1
        blocks.head("content") shouldBe a[Str]
        blocks.head("content").asString shouldBe "Preview of /pages/x"

        // The image rides as a follow-up `user` message carrying an
        // image content block (Provider.renderFrames appends it after the
        // tool_result). Anthropic's URL-image shape is
        // `{"type":"image","source":{"type":"url","url":"…"}}`.
        val messages = body("messages").asVector
        val imageBlocks = messages.flatMap { msg =>
          msg("content").asVector.collect {
            case b if b.get("type").map(_.asString).contains("image") => b
          }
        }
        imageBlocks should not be empty
        imageBlocks.head("source")("url").asString shouldBe sampleUrl.toString
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
