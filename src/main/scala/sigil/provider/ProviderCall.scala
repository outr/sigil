package sigil.provider

import lightdb.id.Id
import sigil.db.Model
import sigil.tool.{Tool, ToolInput}

/**
 * The format-neutral internal representation a [[Provider]]'s `call`
 * receives. The framework's `apply` translates each [[ProviderRequest]]
 * variant (Conversation / OneShot) into a `ProviderCall`, fully
 * resolving any DB-backed references (memories, summaries) along the way.
 *
 * Providers serialize `system` + `messages` + `tools` to their own wire
 * format (OpenAI chat-completions, Anthropic messages, etc.) inside
 * `Provider.call`. They never see [[ProviderRequest]] directly.
 *
 * @param system             pre-rendered system prompt (mode line, topic
 *                           lines, instructions, memories, summaries,
 *                           skills, tool hints, extra context — all
 *                           assembled by the shared translation pass)
 * @param messages           the conversational message log in the
 *                           framework-neutral [[ProviderMessage]] form;
 *                           empty for a OneShot whose user prompt is the
 *                           only payload (already in messages)
 * @param tools              the tools to advertise to the model
 * @param toolChoice         how aggressively to force a tool call
 * @param generationSettings sampling + limits passed through unchanged
 */
case class ProviderCall(modelId: Id[Model],
                        system: String,
                        messages: Vector[ProviderMessage],
                        tools: Vector[Tool[? <: ToolInput]],
                        toolChoice: ToolChoice,
                        generationSettings: GenerationSettings)
