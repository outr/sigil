package sigil.provider

import lightdb.id.Id
import sigil.participant.ParticipantId
import sigil.tool.{Tool, ToolInput}

/**
 * A provider invocation. Two variants — [[ConversationRequest]]
 * for agent turns (carries conversation context, topic stack,
 * instructions, frames) or [[OneShotRequest]] for focused /
 * framework / consult-style calls (just a system prompt + user prompt).
 *
 * Providers never inspect the variant directly. The framework's `apply`
 * is `final` and translates each variant into a uniform [[ProviderCall]]
 * before dispatching to the provider's `call` implementation.
 */
trait ProviderRequest {
  def modelId: Id[sigil.db.Model]
  def generationSettings: GenerationSettings
  def tools: Vector[Tool[? <: ToolInput]]
  def chain: List[ParticipantId]
  def requestId: Id[ProviderRequest]
}
