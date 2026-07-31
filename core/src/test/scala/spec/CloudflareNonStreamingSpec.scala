package spec

import fabric.{Json, Null, arr, bool, num, obj, str}
import fabric.io.JsonFormatter
import fabric.rw.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import rapid.Task
import sigil.db.Model
import sigil.provider.{
  GenerationSettings, ProviderCall, ProviderEvent, StopReason, ToolChoice
}
import sigil.provider.wire.OpenAIChatCompletions
import sigil.tool.{DiscoverySpec, Effect, MutationTargeting, TextToolOutput, Tool, ToolContext, ToolInput, ToolName, ToolProfile, ToolResult, ToolSpec}

import spec.CloudflareNonStreamingSpec.*
import sigil.tool.ToolRoster

/**
 * Non-streaming transport (the `Config.streaming` toggle). Cloudflare's
 * reasoning models drop tool calls on the STREAMING endpoint when the model
 * reasons before deciding — `finish_reason: stop`, no tool_calls — but
 * return the call correctly on a single NON-streaming request. This pins:
 * (1) the per-call / per-provider streaming resolution; (2) `buildBody`
 * drops `stream_options` and sets `stream:false` when non-streaming; and
 * (3) the EXACT CF gpt-oss response shape that streaming mishandled parses,
 * non-streamed, into the tool call + usage + done the orchestrator expects.
 */
class CloudflareNonStreamingSpec extends AnyWordSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  private val cfg: OpenAIChatCompletions.Config = OpenAIChatCompletions.Config(
    providerNamespace        = "cloudflare",
    providerName             = "Cloudflare",
    nonStrictSchemaTransform = identity
  )

  private val model = TestSigil.testModel(Model.id("cloudflare", "@cf/openai/gpt-oss-120b"))

  private def call(gen: GenerationSettings = GenerationSettings()): ProviderCall =
    ProviderCall(
      model              = model,
      system             = "be a coding agent",
      messages           = Vector.empty,
      roster = ToolRoster(Vector(ListFilesTool)),
      builtInTools       = Set.empty,
      toolChoice         = ToolChoice.Auto,
      generationSettings = gen
    )

  "Streaming resolution" should {
    "use the per-call override when set, else the provider default" in {
      OpenAIChatCompletions.effectiveStreaming(call(GenerationSettings(streaming = Some(true))), cfg.copy(streaming = false)) shouldBe true
      OpenAIChatCompletions.effectiveStreaming(call(GenerationSettings(streaming = Some(false))), cfg.copy(streaming = true)) shouldBe false
      OpenAIChatCompletions.effectiveStreaming(call(), cfg.copy(streaming = false)) shouldBe false
      OpenAIChatCompletions.effectiveStreaming(call(), cfg.copy(streaming = true)) shouldBe true
    }
  }

  "buildBody" should {
    "set stream:false and omit stream_options when non-streaming" in {
      val body = OpenAIChatCompletions.buildBody(call(), TestSigil, cfg.copy(streaming = false))
      body.get("stream") shouldBe Some(bool(false))
      body.get("stream_options") shouldBe None
    }
    "set stream:true with stream_options when streaming" in {
      val body = OpenAIChatCompletions.buildBody(call(), TestSigil, cfg.copy(streaming = true))
      body.get("stream") shouldBe Some(bool(true))
      body.get("stream_options") shouldBe defined
    }
  }

  "parseNonStreamBody (the CF gpt-oss response streaming dropped)" should {
    "recover the tool call, usage, and done from the single JSON response" in {
      // The exact shape captured from CF: reasoning_content, then a tool
      // call, finish_reason tool_calls. (Streaming returned stop + no call.)
      val response = JsonFormatter.Compact(obj(
        "id"     -> str("id-1"),
        "object" -> str("chat.completion"),
        "choices" -> arr(obj(
          "index" -> num(0),
          "message" -> obj(
            "role"              -> str("assistant"),
            "content"           -> Null,
            "reasoning_content" -> str("We need to call list_files."),
            "tool_calls" -> arr(obj(
              "id"    -> str("call-1"),
              "type"  -> str("function"),
              "index" -> num(0),
              "function" -> obj("name" -> str("list_files"), "arguments" -> str("{}"))
            ))
          ),
          "finish_reason" -> str("tool_calls")
        )),
        "usage" -> obj("prompt_tokens" -> num(152), "completion_tokens" -> num(27), "total_tokens" -> num(179))
      ))

      val events = OpenAIChatCompletions.parseNonStreamBody(response, call(), cfg)

      events.collectFirst { case ProviderEvent.ThinkingDelta(t) => t } shouldBe Some("We need to call list_files.")
      events.collectFirst { case ProviderEvent.ToolCallStart(_, n) => n } shouldBe Some("list_files")
      events.flatMap { case ProviderEvent.ToolCallComplete(_, wc) => wc.decodedInput; case _ => None }.collectFirst { case in: ListFilesInput => in } shouldBe Some(ListFilesInput())
      events.exists { case _: ProviderEvent.Usage => true; case _ => false } shouldBe true
      events.collectFirst { case ProviderEvent.Done(sr) => sr } shouldBe Some(StopReason.ToolCall)
    }
  }
}

object CloudflareNonStreamingSpec {
  case class ListFilesInput() extends ToolInput derives RW
  ToolInput.register(RW.static(ListFilesInput()))

  case object ListFilesTool extends Tool {
    type Input  = ListFilesInput
    type Output = TextToolOutput
    val inputRW  = summon[RW[ListFilesInput]]
    val outputRW = summon[RW[TextToolOutput]]
    override val name = ToolName("list_files")
    override val description = "List the project files."
    val spec: ToolSpec = ToolSpec(
      name = name,
      description = description,
      profile = ToolProfile(effect = Effect.Mutating(MutationTargeting.none)),
      discovery = DiscoverySpec(keywords = Set("test", "list", "files"))
    )
    override def executeResult(input: ListFilesInput, ctx: ToolContext): Task[ToolResult[TextToolOutput]] =
      Task.pure(ToolResult.Success(TextToolOutput("")))
  }
}
