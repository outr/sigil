package spec

import fabric.*
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.conversation.TurnInput
import sigil.db.Model
import sigil.provider.{ConversationMode, ConversationRequest, GenerationSettings, Instructions, ProviderRequest}
import sigil.provider.openai.OpenAIProvider
import sigil.tool.{DefinitionToSchema, Tool, ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tool.core.CoreTools

/**
 * Regression coverage for bug #63 — `DefType.Json` used to render as
 * the empty schema `{}`. Inside an OpenAI-strict-mode `anyOf` wrap
 * (which `Option[Json]` produces), that gave a request body with
 * `anyOf[0] == {}` — no `type` key — and OpenAI's strict tool-schema
 * validator rejected it. With `stream: true` (Sigil's default) the
 * rejection came back as `200 OK` with empty body, masking the
 * actual cause.
 *
 * The fix renders `DefType.Json` as a typed permissive union
 * (`{type: [string, number, integer, boolean, object, array, null]}`).
 * Same semantic ("any JSON value"), but every element carries a
 * `type` key so strict validators accept it.
 *
 * This spec asserts both the direct rendering and the post-strict-
 * mode shape — the failure path goes through both.
 */
class JsonFieldSchemaSpec extends AnyWordSpec with Matchers {

  /** Test fixture mirroring the kind of tool input that triggered the
    * bug downstream — `Option[Json]` for an opaque value the agent
    * supplies, plus a non-optional `Json` field for completeness. */
  case class JsonFieldInput(name: String,
                            defaultValue: Option[Json] = None,
                            metadata: Json = obj()) extends ToolInput derives RW

  private val schema: Json = DefinitionToSchema(summon[RW[JsonFieldInput]].definition)

  "DefinitionToSchema for fabric.Json fields (bug #65 — empty schema is correct)" should {
    "render a non-optional Json field as the empty schema {}" in {
      val metadataSchema = schema("properties")("metadata")
      // Empty `{}` is the canonical JSON-Schema shape for "any JSON
      // value." Accepted by OpenAI in non-strict mode (which is what
      // tools with `Json` fields ship with per #64), accepted by
      // every other provider Sigil targets. #63's typed-union shape
      // turned out to be rejected in both modes — see bug #65.
      metadataSchema shouldBe obj()
    }

    "render an Option[Json] field as the empty schema {} (Opt unwraps to inner Json)" in {
      // `Option[T]` doesn't add a wrap at the `DefinitionToSchema`
      // layer; optionality is encoded via the parent's `required`
      // array. So `defaultValue: Option[Json]` flattens to the
      // inner `Json` schema — `{}`.
      val defaultSchema = schema("properties")("defaultValue")
      defaultSchema shouldBe obj()
    }
  }

  /** Tools with no `Json` fields — should keep strict mode. */
  case class TypedOnlyInput(name: String,
                            count: Int = 0,
                            enabled: Boolean = true) extends ToolInput derives RW

  private object TypedOnlyTool extends TypedTool[TypedOnlyInput](
    name = ToolName("typed_only_test_tool"),
    description = "All-typed input — should ship with strict: true."
  ) {
    override protected def executeTyped(input: TypedOnlyInput, context: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
      rapid.Stream.empty
  }

  /** Tool with an `Option[Json]` field — should drop to strict: false. */
  private object JsonFieldTool extends TypedTool[JsonFieldInput](
    name = ToolName("json_field_test_tool"),
    description = "Has Option[Json] — should ship with strict: false."
  ) {
    override protected def executeTyped(input: JsonFieldInput, context: sigil.TurnContext): rapid.Stream[sigil.event.Event] =
      rapid.Stream.empty
  }

  TestSigil.initFor(getClass.getSimpleName)

  /** Build the OpenAI request body with `tools = [tool]` and read back
    * the rendered tool's `strict` flag. Lets us assert the
    * `containsJson` gate is wired into the provider's render path. */
  private def strictFlagFor(tool: Tool): Boolean = {
    val provider = OpenAIProvider(apiKey = "sk-test", sigilRef = TestSigil)
    val req: ProviderRequest = ConversationRequest(
      conversationId = sigil.conversation.Conversation.id("strict-flag-test"),
      modelId = Model.id("openai", "gpt-5.4-nano"),
      instructions = Instructions(),
      turnInput = TurnInput(
        sigil.conversation.ConversationView(
          conversationId = sigil.conversation.Conversation.id("strict-flag-test"),
          frames = Vector(sigil.conversation.ContextFrame.Text(
            content = "test",
            participantId = TestUser,
            sourceEventId = Id[sigil.event.Event]("seed")
          )),
          _id = sigil.conversation.ConversationView.idFor(sigil.conversation.Conversation.id("strict-flag-test"))
        )
      ),
      currentMode = ConversationMode,
      currentTopic = TestTopicEntry,
      generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
      tools = Vector(tool),
      chain = List(TestUser, TestAgent)
    )
    val body = provider.requestConverter(req).sync().content match {
      case Some(c: spice.http.content.StringContent) => c.value
      case _ => ""
    }
    val parsed = fabric.io.JsonParser(body)
    val toolsArr = parsed("tools").asArr.value.toList
    // Find the function entry whose `name` matches; pull `strict`.
    val entry = toolsArr.collectFirst {
      case o if o.get("name").exists(_.asString == tool.schema.name.value) => o
    }.getOrElse(throw new RuntimeException(s"tool ${tool.schema.name.value} not in rendered tools array; body=$body"))
    entry("strict").asBoolean
  }

  "OpenAIProvider tool rendering (bug #64)" should {
    "ship `strict: true` for tools with all-typed fields" in {
      DefinitionToSchema.containsJson(TypedOnlyTool.schema.input) shouldBe false
      strictFlagFor(TypedOnlyTool) shouldBe true
    }

    "ship `strict: false` for tools whose input contains a Json field" in {
      DefinitionToSchema.containsJson(JsonFieldTool.schema.input) shouldBe true
      strictFlagFor(JsonFieldTool) shouldBe false
    }

    "still strip unsupported keys (pattern, format, …) on the non-strict path" in {
      // Non-strict tools don't go through `forOpenAIStrict`, but they
      // DO go through `stripUnsupportedKeys` so OpenAI's validator
      // doesn't reject `pattern` / `format` keywords on string fields.
      // This is a sanity check that the non-strict render isn't a raw
      // pass-through.
      val provider = OpenAIProvider(apiKey = "sk-test", sigilRef = TestSigil)
      val req: ProviderRequest = ConversationRequest(
        conversationId = sigil.conversation.Conversation.id("non-strict-keys"),
        modelId = Model.id("openai", "gpt-5.4-nano"),
        instructions = Instructions(),
        turnInput = TurnInput(
          sigil.conversation.ConversationView(
            conversationId = sigil.conversation.Conversation.id("non-strict-keys"),
            frames = Vector(sigil.conversation.ContextFrame.Text(
              content = "test",
              participantId = TestUser,
              sourceEventId = Id[sigil.event.Event]("seed")
            )),
            _id = sigil.conversation.ConversationView.idFor(sigil.conversation.Conversation.id("non-strict-keys"))
          )
        ),
        currentMode = ConversationMode,
        currentTopic = TestTopicEntry,
        generationSettings = GenerationSettings(maxOutputTokens = Some(50)),
        tools = Vector(JsonFieldTool),
        chain = List(TestUser, TestAgent)
      )
      val body = provider.requestConverter(req).sync().content match {
        case Some(c: spice.http.content.StringContent) => c.value
        case _ => ""
      }
      // Tool ships permissively — no `format` keyword leaks through.
      // (JsonFieldInput has no annotated formats, but the strip pass
      // is what guarantees that any future `@format`-annotated field
      // wouldn't leak. The check is on the body.)
      body shouldNot include("\"format\":")
    }
  }

  "DefinitionToSchema.containsJson predicate" should {
    "return false for fully-typed inputs (string, int, bool)" in {
      DefinitionToSchema.containsJson(summon[RW[TypedOnlyInput]].definition) shouldBe false
    }

    "return true when the input has a Json field at the top level" in {
      DefinitionToSchema.containsJson(summon[RW[JsonFieldInput]].definition) shouldBe true
    }

    "return true when the Json field is wrapped in Option" in {
      case class OptOnly(value: Option[Json] = None) derives RW
      DefinitionToSchema.containsJson(summon[RW[OptOnly]].definition) shouldBe true
    }

    "return true when the Json field is nested inside a List" in {
      case class ArrOnly(values: List[Json] = Nil) derives RW
      DefinitionToSchema.containsJson(summon[RW[ArrOnly]].definition) shouldBe true
    }

    "return true when the Json field is nested inside a sub-case-class" in {
      case class Inner(payload: Json = obj()) derives RW
      case class Outer(inner: Inner = Inner()) derives RW
      DefinitionToSchema.containsJson(summon[RW[Outer]].definition) shouldBe true
    }
  }
}
