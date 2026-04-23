package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{BuiltInTool, ConversationRequest, GenerationSettings, Instructions, Mode, ProviderEvent}
import sigil.provider.openai.OpenAIProvider
import sigil.tool.core.CoreTools

/**
 * Live OpenAI web-search coverage. Asserts the provider emits
 * [[ProviderEvent.ServerToolStart]] and [[ProviderEvent.ServerToolComplete]]
 * for [[BuiltInTool.WebSearch]] when the request opts in.
 *
 * Skipped cleanly when `OPENAI_API_KEY` is unset.
 */
class OpenAIWebSearchSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    OpenAILiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  "OpenAI web search" should {
    "emit ServerToolStart + ServerToolComplete for BuiltInTool.WebSearch" in {
      val provider = OpenAIProvider.create(TestSigil, OpenAILiveSupport.apiKey.get).sync()

      val convId = Conversation.id(s"ws-${rapid.Unique()}")
      val view = ConversationView(
        conversationId = convId,
        frames = Vector(ContextFrame.Text(
          content = "What is today's top news story? Be concise.",
          participantId = TestUser,
          sourceEventId = Id[Event]("u-1")
        )),
        _id = ConversationView.idFor(convId)
      )
      val request = ConversationRequest(
        conversationId = convId,
        modelId = Model.id("openai", "gpt-5.4-nano"),
        instructions = Instructions(),
        turnInput = TurnInput(view),
        currentMode = Mode.Conversation,
        currentTopic = TestTopicEntry,
        generationSettings = GenerationSettings(maxOutputTokens = Some(500), temperature = Some(0.0)),
        tools = CoreTools.all,
        builtInTools = Set(BuiltInTool.WebSearch),
        chain = List(TestUser, TestAgent)
      )

      provider(request).toList.map { events =>
        val serverToolStarts = events.collect { case s: ProviderEvent.ServerToolStart => s }
        val serverToolCompletes = events.collect { case s: ProviderEvent.ServerToolComplete => s }
        withClue(s"events: ${events.map(_.asString).mkString(" | ")}") {
          serverToolStarts.exists(_.tool == BuiltInTool.WebSearch) shouldBe true
          serverToolCompletes.exists(_.tool == BuiltInTool.WebSearch) shouldBe true
        }
      }
    }
  }
}
