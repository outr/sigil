package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Stream, Task}
import sigil.Sigil
import sigil.conversation.{Conversation, TurnInput}
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.orchestrator.Orchestrator
import sigil.provider.{
  CallId, ConversationMode, ConversationRequest, GenerationSettings, Instructions,
  Provider, ProviderCall, ProviderEvent, ProviderType, StopReason
}
import sigil.signal.Signal
import sigil.tool.core.RespondTool
import sigil.tool.model.ResponseContent
import spice.http.HttpRequest

/**
 * Sigil #410 — a raw provider error carries vendor-identifying content (the
 * backend's name and support URLs like `help.openai.com`), which the framework
 * inserts verbatim into the agent-facing diagnostic (the model can echo it) and,
 * on a non-agent-routed failure, into the user's own reply bubble. Apps that
 * present a provider-agnostic product need a seam to scrub it. `sanitizeProviderError`
 * is that seam; the default strips known vendor support URLs, and it is applied
 * wherever the raw error becomes surfaced content.
 */
class ProviderErrorSanitizeSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val vendorError =
    "An error occurred while processing your request. You can retry your request, or " +
      "contact us through our help center at help.openai.com if the error persists."

  private val modelId: Id[Model] = Model.id("openai", "gpt-5.4-mini")
  TestSigil.testModel(modelId)

  private class ErrorProvider extends Provider {
    override def `type`: ProviderType = ProviderType.LlamaCpp
    override def models: List[Model] = Nil
    override protected def sigil: _root_.sigil.Sigil = TestSigil
    override def httpRequestFor(input: ProviderCall): Task[HttpRequest] =
      Task.error(new UnsupportedOperationException("no wire"))
    override def call(input: ProviderCall): Stream[ProviderEvent] =
      Stream.emits(List(
        ProviderEvent.Error(vendorError),
        ProviderEvent.Done(StopReason.Complete)
      ))
  }

  "the default sanitizeProviderError (#410)" should {

    "strip a known vendor support URL while keeping the rest of the diagnostic" in Task {
      val out = TestSigil.sanitizeProviderError("openai", vendorError)
      out should not include "openai.com"
      out should not include "help.openai"
      out should include("retry your request") // actionable content survives
    }

    "leave a vendorless error untouched" in Task {
      val plain = "Rate limited: too many requests, retry after 2s."
      TestSigil.sanitizeProviderError("openai", plain) shouldBe plain
    }

    "let an app override scrub to a fully generic message" in Task {
      val host = new Sigil {
        override type DB = sigil.db.DefaultSigilDB
        override protected def buildDB(directory: Option[java.nio.file.Path],
                                       storeManager: lightdb.store.CollectionManager,
                                       appUpgrades: List[lightdb.upgrade.DatabaseUpgrade]): DB =
          new sigil.db.DefaultSigilDB(directory, storeManager, appUpgrades)
        override def modelResolver: sigil.provider.ModelResolver = _ => None
        override def sanitizeProviderError(providerName: String, raw: String): String =
          "The model provider returned an error. Please retry."
      }
      host.sanitizeProviderError("openai", vendorError) shouldBe "The model provider returned an error. Please retry."
    }
  }

  "a surfaced provider error (#410)" should {

    "not leak the vendor domain into the agent-facing Tool-role Message" in {
      val convId = Conversation.id(s"proverr-${rapid.Unique()}")
      val conv = Conversation(topics = TestTopicStack, _id = convId)
      val request = ConversationRequest(
        conversationId = convId,
        model = TestSigil.testModel(modelId),
        instructions = Instructions(),
        turnInput = TurnInput(conversationId = convId),
        currentMode = ConversationMode,
        currentTopic = TestTopicEntry,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
        tools = Vector(RespondTool),
        chain = List(TestUser, TestAgent)
      )
      for {
        _ <- TestSigil.withDB(_.conversations.transaction(_.upsert(conv)))
        signals <- Orchestrator.process(TestSigil, new ErrorProvider, request, conv).toList
      } yield {
        val toolTexts = signals.collect { case m: Message if m.role == MessageRole.Tool => m }
          .flatMap(_.content.collect { case t: ResponseContent.Text => t.text })
        withClue(s"tool-role texts: $toolTexts\n") {
          // The error still surfaces to the agent (it must know it failed)...
          toolTexts.exists(_.contains("Provider error")) shouldBe true
          // ...but the vendor domain is scrubbed everywhere it lands.
          toolTexts.foreach(_ should not include "openai.com")
          succeed
        }
      }
    }
  }
}
