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
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, Provider, ProviderRequest}
import sigil.provider.deepinfra.DeepInfraProvider
import sigil.provider.openai.OpenAIProvider
import sigil.tool.core.{FindCapabilityTool, RespondTool}
import spice.http.content.StringContent

/**
 * Regression for sigil bug #173 — DeepInfra wire posture.
 *
 * DeepInfra accepts `strict: true` (HTTP 200) but doesn't enforce it,
 * and their documented `tool_choice` set is {"auto", "none"} — not
 * `"required"` or the function-form. The fix:
 *
 *   - Strip per-function `strict: true` from the wire (we still
 *     reshape the schema, but don't pretend the backend honors the
 *     flag).
 *   - Substitute `tool_choice: "required"` and function-form with
 *     `response_format: {type: "json_schema", json_schema: …}` over a
 *     synthesized meta-schema, preserving Sigil's structure-first
 *     forced-call invariant via DeepInfra's documented mechanism.
 *
 * This spec verifies the OUTBOUND wire body shape for both forced-
 * call variants and contrasts with OpenAI (which keeps the standard
 * `tool_choice` path). It does NOT exercise the stream-side
 * content-to-toolcall synthesis (that lives in a separate spec).
 */
class DeepInfraWirePostureSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val conversationId = sigil.conversation.Conversation.id("deepinfra-wire-conv")
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

  "DeepInfraProvider" should {

    val provider: Task[Provider] = DeepInfraProvider.create(TestSigil, "test-key").map(p => p: Provider)
    val modelId = Model.id(sigil.provider.deepinfra.DeepInfra.Provider, "moonshotai/Kimi-K2.5")

    "omit per-function `strict: true` from the wire (honorsStrict = false)" in
      bodyOf(provider, modelId, forced = false).map { body =>
        val tools = body("tools").asVector
        tools should not be empty
        tools.foreach { t =>
          val fn = t("function").asObj.value
          withClue(s"tool ${fn.get("name").map(_.asString).getOrElse("?")} must NOT include `strict`: ") {
            fn.contains("strict") shouldBe false
          }
        }
        succeed
      }

    "substitute response_format:json_schema for ToolChoice.Required (meta-schema)" in
      bodyOf(provider, modelId, forced = false).map { body =>
        val bodyObj = body.asObj.value
        // No tool_choice at all (substituted by response_format).
        bodyObj.contains("tool_choice") shouldBe false
        // response_format must be present and well-formed.
        val rf = body("response_format")
        rf("type").asString shouldBe "json_schema"
        val js = rf("json_schema")
        js("name").asString shouldBe "sigil_tool_call"
        js("strict").asBoolean shouldBe true
        val schema = js("schema")
        schema("type").asString shouldBe "object"
        // tool_name enum carries all roster names.
        val toolNameEnum = schema("properties")("tool_name")("enum").asVector.map(_.asString).toSet
        toolNameEnum should contain allOf ("respond", "find_capability")
        // arguments is a oneOf across the roster's input schemas.
        val argsSchema = schema("properties")("arguments")
        val oneOf = argsSchema("oneOf").asVector
        oneOf should have size 2
      }

    "substitute response_format:json_schema with the respond-family meta-schema under forced response synthesis" in
      bodyOf(provider, modelId, forced = true).map { body =>
        val bodyObj = body.asObj.value
        bodyObj.contains("tool_choice") shouldBe false
        // Force now leaves a tool roster filtered to the respond family
        // and routes through DeepInfra's response_format substitution.
        // The meta-schema's tool_name enum carries the family members.
        val rf = body("response_format")
        rf("type").asString shouldBe "json_schema"
        val js = rf("json_schema")
        js("strict").asBoolean shouldBe true
        val schema = js("schema")
        schema("type").asString shouldBe "object"
        val props = schema("properties").asObj.value
        props.keys should contain("tool_name")
        props.keys should contain("arguments")
      }
  }

  "OpenAIProvider" should {

    val provider: Provider = OpenAIProvider(apiKey = "sk-test", sigilRef = TestSigil)
    val modelId = Model.id("openai", "gpt-4o-mini")

    "keep `strict: true` on the wire (honorsStrict default = true)" in
      // OpenAI honors `tool_choice` natively, so the standard path stays.
      // OpenAI uses the Responses API, not chat-completions — strict is
      // emitted directly there. Just verify per-tool strict survives:
      provider.requestConverter(requestWith(modelId, forced = false)).map { req =>
        val s = req.content match {
          case Some(c: StringContent) => c.value
          case _ => ""
        }
        s should include("\"strict\":true")
      }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed)
  }
}
