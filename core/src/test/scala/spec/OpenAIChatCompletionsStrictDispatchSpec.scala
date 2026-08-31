package spec

import fabric.Json
import fabric.rw.*
import lightdb.id.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sigil.db.Model
import sigil.provider.{ConversationMode, GenerationSettings, ProviderCall, ProviderMessage, SchemaDialect, ToolChoice}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.tool.ToolContext
import sigil.tool.{
  DiscoverySpec,
  Effect,
  JsonInput,
  MutationTargeting,
  Resolution,
  TextToolOutput,
  Tool,
  ToolIO,
  ToolName,
  ToolProfile,
  ToolResult,
  ToolSpec
}
import rapid.Task
import sigil.tool.ToolRoster

/**
 * Coverage for `OpenAIChatCompletions.renderTools` per-tool dialect
 * dispatch. Three rules to enforce:
 *
 *   1. [[SchemaDialect.OpenAIStrict]] + strict-compatible tool → emits
 *      `strict: true` and a strict-shaped schema (every property
 *      required, `additionalProperties: false`, no `pattern` /
 *      numeric-bound keywords).
 *   2. [[SchemaDialect.OpenAIStrict]] + tool with `DefType.Json`
 *      somewhere → omits `strict`, falls back to the lenient shape
 *      INSIDE the dialect. Strict mode is mutually exclusive with
 *      any-JSON-value fields because strict requires every
 *      "object"-typed branch to declare its own closed `properties` +
 *      `additionalProperties: false`.
 *   3. A non-strict dialect → never emits `strict`, regardless of the
 *      tool's input shape, and ships the dialect's transform verbatim.
 */
class OpenAIChatCompletionsStrictDispatchSpec extends AnyWordSpec with Matchers {

  TestSigil.initFor(getClass.getSimpleName)

  /**
   * A tool with a primitive input shape — strict-compatible. We re-use
   * `RespondTool`'s `RespondInput` rather than minting a new case class:
   * its schema is the canonical "no DefType.Json" example.
   */
  private val typedTool: sigil.tool.Tool = sigil.tool.core.RespondTool

  /**
   * A tool whose input is the framework's `JsonInput` carrier — its
   * schema is `DefType.Json` at the root, so `containsJson` returns
   * true and strict mode opts out.
   */
  private object JsonyTool extends Tool {
    type Input = JsonInput
    type Output = TextToolOutput
    val io: ToolIO[JsonInput, TextToolOutput] = ToolIO.derived[JsonInput, TextToolOutput]
    override val name = ToolName("test_json_tool")
    override val description = "Test tool with a Json root input."
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "test_json_tool"))
    )
    protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

    private def executeResult(input: JsonInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("ok")))
  }

  /**
   * Marker dialect standing in for an app-custom transform — proves
   * the renderer ships the dialect's output verbatim and never bolts
   * strict-mode reshaping on after.
   */
  final private class MarkerDialect(marker: String) extends SchemaDialect {
    val name: String = "marker"
    def transform(canonical: Json, containsOpenJson: Boolean): Json =
      fabric.obj("type" -> fabric.str(marker))
  }

  private val call: ProviderCall = ProviderCall(
    model = TestSigil.testModel(Model.id("test", "tools-dispatch-model")),
    system = "test system",
    messages = Vector(ProviderMessage.User(Vector(sigil.provider.MessageContent.Text("hi")))),
    roster = ToolRoster(Vector(typedTool, JsonyTool)),
    builtInTools = Set.empty,
    toolChoice = ToolChoice.Auto,
    generationSettings = GenerationSettings(),
    currentMode = ConversationMode
  )

  private def renderToolByName(config: OpenAIChatCompletions.Config, name: String): Json = {
    val arr = OpenAIChatCompletions.renderTools(call, TestSigil, config)
    arr.find(_("function")("name").asString == name)
      .getOrElse(throw new AssertionError(s"tool '$name' not in rendered output"))
  }

  "SchemaDialect.OpenAIStrict" should {

    val cfg = OpenAIChatCompletions.Config(
      providerNamespace = "test",
      providerName = "Test",
      schemaDialect = SchemaDialect.OpenAIStrict
    )

    "emit strict:true on a strict-compatible tool" in {
      val rendered = renderToolByName(cfg, "respond")
      rendered("function")("strict").asBoolean shouldBe true
    }

    "emit a strict-shaped schema on a strict-compatible tool" in {
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

    "ship the wire schema the dialect produced for both branches" in {
      // The Json-rooted tool takes the lenient branch inside the
      // dialect; the strict-compatible tool takes the strict branch —
      // both must match the dialect's own output exactly.
      renderToolByName(cfg, "test_json_tool")("function")("parameters") shouldBe
        SchemaDialect.OpenAIStrict(JsonyTool)
      renderToolByName(cfg, "respond")("function")("parameters") shouldBe
        SchemaDialect.OpenAIStrict(typedTool)
    }
  }

  "a non-strict dialect" should {

    val cfg = OpenAIChatCompletions.Config(
      providerNamespace = "test",
      providerName = "Test"
    )

    "never emit strict, even on a strict-compatible tool" in {
      val rendered = renderToolByName(cfg, "respond")
      val fn = rendered("function").asObj.value
      fn.contains("strict") shouldBe false
    }

    "apply the dialect transform to every tool" in {
      val marker = "__nonstrict_only__"
      val markerCfg = cfg.copy(schemaDialect = new MarkerDialect(marker))
      renderToolByName(markerCfg, "respond")("function")("parameters")("type").asString shouldBe marker
      renderToolByName(markerCfg, "test_json_tool")("function")("parameters")("type").asString shouldBe marker
    }

    "preserve the schema returned by the dialect verbatim" in {
      // The function's schema body is exactly what the configured
      // dialect produced — no strict-mode reshaping bolted on after.
      val marker = "__nonstrict_only__"
      val markerCfg = cfg.copy(schemaDialect = new MarkerDialect(marker))
      renderToolByName(markerCfg, "respond")("function")("parameters") shouldBe
        fabric.obj("type" -> fabric.str(marker))
    }
  }

  "tear down" should {
    "dispose TestSigil" in TestSigil.shutdown.map(_ => succeed).sync()
  }
}
