package spec

import lightdb.id.Id
import sigil.conversation.TurnInput
import sigil.db.Model
import sigil.provider.{ConversationRequest, ConversationMode, GenerationSettings, Instructions, Provider, ProviderRequest}
import sigil.provider.openai.OpenAIProvider
import sigil.tool.core.CoreTools

/**
 * OpenAI Responses wire-body regression, layered on the shared
 * [[AbstractRequestCoverageSpec]]. Marker substrings are
 * provider-agnostic; this spec adds no OpenAI-specific wire
 * assertions (the Responses API uses a different field layout than
 * chat-completions, so specific-key tests belong here rather than in
 * the abstract base).
 *
 * Deterministic — does not actually hit the OpenAI API. Uses a
 * placeholder key since `requestConverter` only builds the HTTP
 * request without sending it.
 */
class OpenAIRequestCoverageSpec extends AbstractRequestCoverageSpec {
  override protected def providerInstance: Provider =
    OpenAIProvider(apiKey = "sk-test-placeholder", sigilRef = TestSigil)
  override protected def modelId: Id[Model] = Model.id("openai", "gpt-5.4-nano")

  /** Bug #61 — OpenAI's Responses API requires reasoning items from
    * prior turns to be replayed in the next request's `input` array;
    * the abstract spec's Reasoning-frame coverage test asserts the
    * serialized shape when this is `true`. */
  override protected def expectsReasoningSerialized: Boolean = true

  /** Build a request body using a different modelId than the spec's
    * default — used to exercise the reasoning-family vs non-reasoning
    * gating in `buildBody`. */
  private def bodyWithModelId(targetModelId: Id[Model]): String = {
    val req: ProviderRequest = ConversationRequest(
      conversationId = conversationId,
      modelId = targetModelId,
      instructions = Instructions(),
      turnInput = TurnInput(emptyView),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50), temperature = Some(0.0)),
      tools = CoreTools.all,
      chain = List(TestUser, TestAgent)
    )
    providerInstance.requestConverter(req).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _ => ""
    }
  }

  s"${getClass.getSimpleName}.requestConverter (bug #62)" should {
    "ask for reasoning.summary='auto' and include 'reasoning.encrypted_content' for gpt-5 family models" in {
      // The default modelId (`gpt-5.4-nano`) is a reasoning-family model.
      val body = bodyOf(TurnInput(emptyView))
      body should include("\"reasoning\":{")
      body should include("\"summary\":\"auto\"")
      body should include("\"include\":[\"reasoning.encrypted_content\"]")
    }

    "ask for reasoning.summary='auto' and include 'reasoning.encrypted_content' for o1 / o3 / o4 models" in {
      List("o1-preview", "o3-mini", "o4-pilot").foreach { name =>
        val body = bodyWithModelId(Model.id("openai", name))
        withClue(s"model=$name body=$body") {
          body should include("\"summary\":\"auto\"")
          body should include("\"include\":[\"reasoning.encrypted_content\"]")
        }
      }
    }

    "NOT add reasoning or include for non-reasoning models (gpt-4o, gpt-4.1, gpt-3.5)" in {
      List("gpt-4o-mini", "gpt-4.1", "gpt-3.5-turbo").foreach { name =>
        val body = bodyWithModelId(Model.id("openai", name))
        withClue(s"model=$name body=$body") {
          // The `reasoning` field is gated entirely off for non-reasoning
          // models with no explicit `effort` setting — neither the
          // `summary` opt-in nor the `include` opt-in fires.
          body shouldNot include("\"reasoning\":{")
          body shouldNot include("\"include\":")
          body shouldNot include("\"summary\":\"auto\"")
        }
      }
    }
  }
}
