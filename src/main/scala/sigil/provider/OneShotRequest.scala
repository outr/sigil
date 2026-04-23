package sigil.provider

import lightdb.id.Id
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolInput}

/**
 * A focused, one-shot provider request — used by framework sub-decisions
 * (topic classifier, summarizer) and exposed to LLMs via
 * [[sigil.tool.consult.ConsultTool]] for cross-model consultation.
 *
 * Carries no conversation context: just a system prompt, a user prompt,
 * optional tools, and generation settings. The provider builds a clean
 * chat completion with `[system, user]` messages and (when tools are
 * supplied) `tool_choice = "required"`.
 */
case class OneShotRequest(modelId: Id[Model],
                                  systemPrompt: String,
                                  userPrompt: String,
                                  generationSettings: GenerationSettings = GenerationSettings(),
                                  tools: Vector[Tool[? <: ToolInput]] = Vector.empty,
                                  chain: List[ParticipantId] = Nil,
                                  requestId: Id[ProviderRequest] = Id())
  extends ProviderRequest
