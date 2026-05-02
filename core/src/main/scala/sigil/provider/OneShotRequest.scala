package sigil.provider

import lightdb.id.Id
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.Tool
import sigil.tool.model.ResponseContent

/**
 * A focused, one-shot provider request — used by framework sub-decisions
 * (topic classifier, summarizer) and exposed to LLMs via
 * [[sigil.tool.consult.ConsultTool]] for cross-model consultation.
 *
 * Carries no conversation context: just a system prompt, a user input,
 * optional tools, and generation settings. The provider builds a clean
 * chat completion with `[system, user]` messages and (when tools are
 * supplied) `tool_choice = "required"`.
 *
 * **User input shapes (text-only vs. multimodal):**
 *
 *   - For text-only requests, leave [[userContent]] empty and supply
 *     [[userPrompt]]. The framework wraps the string as a single
 *     `Text` content block on the wire.
 *   - For multimodal requests (vision-capable models like GPT-4o,
 *     Claude 3 / 4, Gemini), populate [[userContent]] with text +
 *     image blocks. When `userContent.nonEmpty`, [[userPrompt]] is
 *     ignored — the content vector is the authoritative input.
 *
 * Providers that don't support image content (DeepSeek, llama.cpp
 * without a vision-capable model) drop image blocks at wire-render
 * time and forward only the text content. Apps that need to detect
 * this should pre-check the target model's `architecture.modality`.
 */
case class OneShotRequest(modelId: Id[Model],
                          systemPrompt: String,
                          userPrompt: String,
                          userContent: Vector[ResponseContent] = Vector.empty,
                          generationSettings: GenerationSettings = GenerationSettings(),
                          tools: Vector[Tool] = Vector.empty,
                          builtInTools: Set[BuiltInTool] = Set.empty,
                          chain: List[ParticipantId] = Nil,
                          requestId: Id[ProviderRequest] = Id())
  extends ProviderRequest
