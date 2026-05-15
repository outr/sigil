package spec

import fabric.*
import fabric.io.JsonParser
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import rapid.{AsyncTaskSpec, Task}
import sigil.conversation.{ConversationView, ContextFrame, TurnInput}
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{
  ConversationMode, ConversationRequest, GenerationSettings, Instructions, Provider, ProviderRequest
}
import sigil.provider.openrouter.{OpenRouter, OpenRouterProvider, OpenRouterProviderRouting}
import sigil.tool.core.{FindCapabilityTool, RespondTool}
import spice.http.content.StringContent

/**
 * Wire-posture coverage for [[OpenRouterProvider]]. OpenRouter speaks
 * the canonical OpenAI chat-completions superset — `strict: true` is
 * honored, `tool_choice: "required"` and the function-form are both
 * accepted, no `response_format` substitution. Verifies the outbound
 * wire body keeps both flags end-to-end (no DeepInfra-style stripping)
 * and that optional attribution headers are emitted when configured.
 */
class OpenRouterWirePostureSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val conversationId = sigil.conversation.Conversation.id("openrouter-wire-conv")
  private val view = ConversationView(
    conversationId = conversationId,
    frames = Vector(ContextFrame.Text(
      content = "hello",
      participantId = TestUser,
      sourceEventId = Id[Event]("seed")
    ))
  )

  private def requestWith(modelId: Id[Model], forced: Boolean): ProviderRequest =
    ConversationRequest(
      conversationId = conversationId,
      modelId = modelId,
      instructions = Instructions(),
      turnInput = TurnInput(view),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(100)),
      tools = Vector(RespondTool, FindCapabilityTool),
      forceResponseSynthesis = forced,
      chain = List(TestUser, TestAgent)
    )

  private def bodyOf(provider: Task[Provider], modelId: Id[Model], forced: Boolean): Task[Json] =
    provider.flatMap(_.requestConverter(requestWith(modelId, forced))).map(_.content match {
      case Some(c: StringContent) => JsonParser(c.value)
      case _ => obj()
    })

  private def httpRequestOf(provider: Task[Provider], modelId: Id[Model]): Task[spice.http.HttpRequest] =
    provider.flatMap(_.requestConverter(requestWith(modelId, forced = false)))

  "OpenRouterProvider" should {

    val provider: Task[Provider] = OpenRouterProvider.create(TestSigil, "test-key").map(p => p: Provider)
    // OpenRouter slugs are `<vendor>/<model>` ids (e.g. `openai/gpt-5.2`).
    // The cache stores them under that exact form; the provider sends
    // the id verbatim as the wire `model` field.
    val modelId  = Model.id("openai", "gpt-5.2")

    "emit per-function `strict: true` on the wire (honorsStrict = true)" in {
      bodyOf(provider, modelId, forced = false).map { body =>
        val tools = body("tools").asVector
        tools should not be empty
        tools.foreach { t =>
          val fn = t("function").asObj.value
          withClue(s"tool ${fn.get("name").map(_.asString).getOrElse("?")} must include `strict: true`: ") {
            fn.contains("strict") shouldBe true
            fn("strict").asBoolean shouldBe true
          }
        }
        succeed
      }
    }

    "keep `tool_choice: \"required\"` end-to-end for ToolChoice.Required" in {
      bodyOf(provider, modelId, forced = false).map { body =>
        val bodyObj = body.asObj.value
        // No response_format substitution — OpenRouter honors tool_choice natively.
        bodyObj.contains("response_format") shouldBe false
        body("tool_choice").asString shouldBe "required"
      }
    }

    "emit tool_choice: required with the tool roster filtered to the respond family (forced response synthesis)" in {
      bodyOf(provider, modelId, forced = true).map { body =>
        val bodyObj = body.asObj.value
        bodyObj.contains("response_format") shouldBe false
        // Force now filters c.tools to the atomic-content (respond)
        // family and uses tool_choice: required — the model picks one
        // of respond / respond_options / respond_field / respond_failure
        // / respond_card / respond_cards / no_response.
        body("tool_choice").asString shouldBe "required"
        val toolNames = body("tools").asVector
          .map(_.asObj.value("function").asObj.value("name").asString)
        // Every offered tool is in the atomic-content family.
        val respondFamily = Set(
          "respond", "respond_options", "respond_field", "respond_failure",
          "respond_card", "respond_cards", "no_response"
        )
        toolNames.foreach(n => respondFamily should contain (n))
        succeed
      }
    }

    "send the OpenRouter-canonical model id verbatim (no provider-prefix stripping when not present)" in {
      bodyOf(provider, modelId, forced = false).map { body =>
        body("model").asString shouldBe "openai/gpt-5.2"
      }
    }

    "strip the `openrouter/` namespace prefix when the model id carries it explicitly" in {
      val prefixed = Model.id(OpenRouter.Provider, "openai/gpt-5.2")
      bodyOf(provider, prefixed, forced = false).map { body =>
        // Sent as `openai/gpt-5.2` (the prefix is stripped) — matches the
        // OpenRouter catalog's native slug form.
        body("model").asString shouldBe "openai/gpt-5.2"
      }
    }

    "POST to /api/v1/chat/completions" in {
      httpRequestOf(provider, modelId).map { req =>
        req.url.toString should endWith ("/api/v1/chat/completions")
      }
    }

    "carry the Authorization: Bearer header" in {
      httpRequestOf(provider, modelId).map { req =>
        val auth = req.headers.first(spice.http.StringHeaderKey("Authorization")).getOrElse("")
        auth shouldBe "Bearer test-key"
      }
    }

    "omit attribution headers by default" in {
      httpRequestOf(provider, modelId).map { req =>
        req.headers.first(spice.http.StringHeaderKey("HTTP-Referer")) shouldBe None
        req.headers.first(spice.http.StringHeaderKey("X-Title")) shouldBe None
      }
    }

    "emit the geographic-restriction `provider.ignore` deny-list by default" in {
      bodyOf(provider, modelId, forced = false).map { body =>
        // The default routing (`OpenRouterProviderRouting.noChineseHosting`)
        // populates `ignore` with every slug in `OpenRouter.ChineseHostedSlugs`.
        // Verify the wire body carries it so traffic never routes to a
        // mainland-China-hosted upstream.
        val routing = body("provider").asObj.value
        val ignore = routing("ignore").asVector.map(_.asString).toSet
        ignore shouldBe OpenRouter.ChineseHostedSlugs
        // No `only` / `order` / other knobs unless the app sets them.
        routing.contains("only") shouldBe false
        routing.contains("order") shouldBe false
      }
    }
  }

  "OpenRouterProvider with attribution headers" should {

    val provider: Task[Provider] = OpenRouterProvider.create(
      TestSigil,
      apiKey = "test-key",
      httpReferer = Some("https://example.org/sigil"),
      xTitle = Some("Sigil Test Suite")
    ).map(p => p: Provider)
    val modelId = Model.id("openai", "gpt-5.2")

    "emit HTTP-Referer and X-Title when configured" in {
      httpRequestOf(provider, modelId).map { req =>
        req.headers.first(spice.http.StringHeaderKey("HTTP-Referer")) shouldBe Some("https://example.org/sigil")
        req.headers.first(spice.http.StringHeaderKey("X-Title")) shouldBe Some("Sigil Test Suite")
      }
    }
  }

  "OpenRouterProvider with explicit routing override" should {

    val modelId = Model.id("openai", "gpt-5.2")

    "omit the `provider` block entirely when an empty routing is configured" in {
      val provider: Task[Provider] = OpenRouterProvider.create(
        TestSigil,
        apiKey = "test-key",
        providerRouting = OpenRouterProviderRouting()
      ).map(p => p: Provider)
      bodyOf(provider, modelId, forced = false).map { body =>
        body.asObj.value.contains("provider") shouldBe false
      }
    }

    "emit `only` and `order` when set explicitly" in {
      val provider: Task[Provider] = OpenRouterProvider.create(
        TestSigil,
        apiKey = "test-key",
        providerRouting = OpenRouterProviderRouting(
          order = Some(List("openai", "anthropic")),
          only = Some(List("openai", "anthropic", "google"))
        )
      ).map(p => p: Provider)
      bodyOf(provider, modelId, forced = false).map { body =>
        val routing = body("provider").asObj.value
        routing("order").asVector.map(_.asString) shouldBe Vector("openai", "anthropic")
        routing("only").asVector.map(_.asString).toSet shouldBe Set("openai", "anthropic", "google")
        // No `ignore` unless explicitly populated.
        routing.contains("ignore") shouldBe false
      }
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
