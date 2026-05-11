package sigil.provider

import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.participant.ParticipantId
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
 * @param builtInTools       provider-managed tools the model can use
 *                           server-side without a client round-trip
 *                           (web search, image generation, etc.).
 *                           Providers that don't support a given
 *                           built-in drop it silently.
 * @param toolChoice         how aggressively to force a tool call
 * @param generationSettings sampling + limits passed through unchanged
 */
case class ProviderCall(modelId: Id[Model],
                        system: String,
                        messages: Vector[ProviderMessage],
                        tools: Vector[Tool],
                        builtInTools: Set[BuiltInTool],
                        toolChoice: ToolChoice,
                        generationSettings: GenerationSettings,
                        currentMode: Mode = ConversationMode,
                        /** Conversation + agent identity threaded through so providers
                          * with server-side conversation state (OpenAI Responses'
                          * `previous_response_id`, Anthropic prompt caching) can key
                          * their per-(agent, conversation) state. `None` for one-shot
                          * requests. */
                        conversationId: Option[Id[Conversation]] = None,
                        agentId: Option[ParticipantId] = None,
                        /** Prior response id captured from the same (agent, conversation)
                          * pair's most recent turn. When set, OpenAI's Responses provider
                          * adds `previous_response_id` to the request and trims the head
                          * of `messages` by `priorMessageCount` so the wire body carries
                          * only the delta since that response. Other providers ignore. */
                        previousResponseId: Option[String] = None,
                        priorMessageCount: Option[Int] = None)
