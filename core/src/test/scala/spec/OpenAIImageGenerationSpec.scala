package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{ContextFrame, Conversation, ConversationView, TurnInput}
import sigil.db.Model
import sigil.event.{Event, Message}
import sigil.orchestrator.Orchestrator
import sigil.provider.{BuiltInTool, ConversationRequest, GenerationSettings, Instructions, Mode, ConversationMode}
import sigil.provider.openai.OpenAIProvider
import sigil.tool.core.CoreTools
import sigil.tool.model.ResponseContent

/**
 * Live OpenAI image-generation round-trip: request builtInTools =
 * Set(BuiltInTool.ImageGeneration), verify the orchestrator
 * materializes a Message with a [[ResponseContent.Image]] block.
 *
 * Skipped cleanly when `OPENAI_API_KEY` is unset. Uses a trivial
 * prompt to minimize cost; image generation is still slow (several
 * seconds) and billed per image.
 */
class OpenAIImageGenerationSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
    OpenAILiveSupport.runGated(this, testName, args) {
      super.run(testName, args)
    }

  "OpenAI image generation" should {
    "round-trip to a Message with a ResponseContent.Image block" in {
      val provider = OpenAIProvider.create(TestSigil, OpenAILiveSupport.apiKey.get).sync()
      TestSigil.setProvider(rapid.Task.pure(provider))

      val convId = Conversation.id(s"img-${rapid.Unique()}")
      val userText = "Generate a simple image of a small blue circle on a white background."
      val view = ConversationView(
        conversationId = convId,
        frames = Vector(ContextFrame.Text(
          content = userText,
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
        currentMode = ConversationMode,
        currentTopic = TestTopicEntry,
        generationSettings = GenerationSettings(maxOutputTokens = Some(400), temperature = Some(0.0)),
        tools = CoreTools.all,
        builtInTools = Set(BuiltInTool.ImageGeneration),
        chain = List(TestUser, TestAgent)
      )

      Orchestrator.process(TestSigil, provider, request).toList.map { signals =>
        val messages = signals.collect { case m: Message => m }
        val imageMessage = messages.find(_.content.exists {
          case _: ResponseContent.Image => true
          case _ => false
        })
        withClue(s"received messages: ${messages.map(_.content).mkString(" | ")}") {
          imageMessage should not be empty
        }
        val imageContent = imageMessage.get.content.collectFirst {
          case i: ResponseContent.Image => i
        }
        imageContent.get.url.toString should not be empty
      }
    }
  }
}
