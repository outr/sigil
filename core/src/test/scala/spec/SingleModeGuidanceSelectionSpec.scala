package spec

import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.AsyncTaskSpec
import sigil.conversation.{Conversation, TopicEntry, TurnInput}
import sigil.db.Model
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions}
import sigil.provider.cloudflare.{Cloudflare, CloudflareProvider}
import sigil.tool.core.{ChangeModeTool, CoreTools}

/**
 * `Provider.renderSystem` selects the single-mode tools guidance when
 * `change_mode` isn't in the roster, so a single-mode app's system prompt
 * never points the model at a tool it can't call. The multi-mode triage is
 * restored once `change_mode` is present.
 */
class SingleModeGuidanceSelectionSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val provider = CloudflareProvider("test-token", "test-account", TestSigil)
  private val topic    = TopicEntry(sigil.conversation.Topic.id("t"), label = "t", summary = "t")
  private val convId   = Conversation.id("single-mode-guidance-spec")
  private val modelId: Id[Model] = Model.id(Cloudflare.Provider, "@cf/moonshotai/kimi-k2.6")

  private def bodyOf(tools: Vector[sigil.tool.Tool]): rapid.Task[String] = {
    val req = ConversationRequest(
      conversationId     = convId,
      model              = TestSigil.testModel(modelId),
      instructions       = Instructions(),
      turnInput          = TurnInput(conversationId = convId),
      currentMode        = ConversationMode,
      currentTopic       = topic,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      tools              = tools,
      chain              = List(TestUser, TestAgent)
    )
    provider.requestConverter(req).map(_.content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _                                          => ""
    })
  }

  "renderSystem tools-guidance selection" should {

    "use the single-mode variant when change_mode isn't in the roster" in {
      bodyOf(CoreTools.all).map { body =>
        body should include ("discovery-first")
        body should not include ("STEP 0")
        body should not include ("change_mode")
      }
    }

    "use the multi-mode triage when change_mode is in the roster" in {
      bodyOf(CoreTools.all :+ ChangeModeTool).map { body =>
        body should include ("STEP 0")
        body should include ("change_mode")
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
