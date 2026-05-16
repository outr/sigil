package spec

import fabric.Json
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.db.Model
import sigil.event.Event
import sigil.provider.{
  ConversationMode, GenerationSettings, ProviderCall, ProviderMessage, StrictSchema, ToolChoice
}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.tool.{JsonInput, ToolName, TypedTool}
import sigil.tool.model.RespondInput

/**
 * Coverage for `OpenAIChatCompletions.renderTools` per-tool strict-mode
 * dispatch. Three rules to enforce:
 *
 *   1. `strictModeCapable = true` + strict-compatible tool → emits
 *      `strict: true` and a `StrictSchema.forOpenAIStrict`-shaped
 *      schema (every property required, `additionalProperties: false`,
 *      no `pattern` / numeric-bound keywords).
 *   2. `strictModeCapable = true` + tool with `DefType.Json` somewhere
 *      → omits `strict`, falls back to `nonStrictSchemaTransform`.
 *      Bug #64 — strict mode is mutually exclusive with any-JSON-value
 *      fields because strict requires every "object"-typed branch to
 *      declare its own closed `properties` + `additionalProperties:
 *      false`.
 *   3. `strictModeCapable = false` → never emits `strict`, regardless
 *      of the tool's input shape.
 */
class OpenAIChatCompletionsStrictDispatchSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  /** A tool with a primitive input shape — strict-compatible. We re-use
    * `RespondTool`'s `RespondInput` rather than minting a new case class:
    * its schema is the canonical "no DefType.Json" example. */
  private val typedTool: sigil.tool.Tool = sigil.tool.core.RespondTool

  /** A tool whose input is the framework's `JsonInput` carrier — its
    * schema is `DefType.Json` at the root, so `containsJson` returns
    * true and strict mode opts out. */
  private object JsonyTool extends TypedTool[JsonInput](
    name = ToolName("test_json_tool"),
    description = "Test tool with a Json root input."
  ) {
  override def paginate: Boolean = false

    override protected def executeTyped(input: JsonInput, context: sigil.TurnContext): rapid.Stream[Event] =
      rapid.Stream.empty
  }

  private val call: ProviderCall = ProviderCall(
    modelId           = Model.id("test", "tools-dispatch-model"),
    system            = "test system",
    messages          = Vector(ProviderMessage.User(Vector(sigil.provider.MessageContent.Text("hi")))),
    tools             = Vector(typedTool, JsonyTool),
    builtInTools      = Set.empty,
    toolChoice        = ToolChoice.Auto,
    generationSettings = GenerationSettings(),
    currentMode       = ConversationMode
  )

  private def renderToolByName(config: OpenAIChatCompletions.Config, name: String): Json = {
    val arr = OpenAIChatCompletions.renderTools(call, TestSigil, config)
    arr.find(_("function")("name").asString == name)
      .getOrElse(throw new AssertionError(s"tool '$name' not in rendered output"))
  }

  "strictModeCapable = true" should {

    val cfg = OpenAIChatCompletions.Config(
      providerNamespace = "test",
      providerName      = "Test",
      strictModeCapable = true
    )

    "emit strict:true on a strict-compatible tool" in {
      val rendered = renderToolByName(cfg, "respond")
      rendered("function")("strict").asBoolean shouldBe true
    }

    "emit a forOpenAIStrict-shaped schema on a strict-compatible tool" in {
      val rendered = renderToolByName(cfg, "respond")
      val params = rendered("function")("parameters")
      // Every property in the strict-shaped schema must be required.
      val properties = params("properties").asObj.value.keys.toSet
      val required = params("required").asVector.map(_.asString).toSet
      withClue(s"strict schema must require every property; missing: ${properties -- required}") {
        properties.subsetOf(required) shouldBe true
      }
      // No additionalProperties leaks at the root.
      params("additionalProperties").asBoolean shouldBe false
    }

    "omit strict on a tool whose input contains DefType.Json" in {
      val rendered = renderToolByName(cfg, "test_json_tool")
      val fn = rendered("function").asObj.value
      fn.contains("strict") shouldBe false
    }

    "fall back to nonStrictSchemaTransform on a Json-rooted tool" in {
      // Custom marker transform — assert the renderer ran the fallback,
      // not forOpenAIStrict, on the Json-rooted tool.
      val marker = "__nonstrict_marker__"
      val markerCfg = cfg.copy(
        nonStrictSchemaTransform = _ => fabric.obj("type" -> fabric.str(marker))
      )
      val rendered = renderToolByName(markerCfg, "test_json_tool")
      rendered("function")("parameters")("type").asString shouldBe marker
      // The strict-compatible tool should NOT be marked — it stays on the strict path.
      renderToolByName(markerCfg, "respond")("function")("parameters")("type").asString shouldNot be (marker)
    }
  }

  "strictModeCapable = false" should {

    val cfg = OpenAIChatCompletions.Config(
      providerNamespace = "test",
      providerName      = "Test",
      strictModeCapable = false
    )

    "never emit strict, even on a strict-compatible tool" in {
      val rendered = renderToolByName(cfg, "respond")
      val fn = rendered("function").asObj.value
      fn.contains("strict") shouldBe false
    }

    "apply nonStrictSchemaTransform to every tool" in {
      val marker = "__nonstrict_only__"
      val markerCfg = cfg.copy(
        nonStrictSchemaTransform = _ => fabric.obj("type" -> fabric.str(marker))
      )
      renderToolByName(markerCfg, "respond")("function")("parameters")("type").asString shouldBe marker
      renderToolByName(markerCfg, "test_json_tool")("function")("parameters")("type").asString shouldBe marker
    }

    "preserve the schema returned by nonStrictSchemaTransform verbatim" in {
      // The function's schema body is exactly what the configured transform
      // produced — no strict-mode reshaping bolted on after.
      val sentinel = fabric.obj("type" -> fabric.str("__nonstrict_only__"))
      val sentinelCfg = cfg.copy(nonStrictSchemaTransform = _ => sentinel)
      renderToolByName(sentinelCfg, "respond")("function")("parameters") shouldBe sentinel
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
