package sigil.provider

import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Event
import sigil.tool.{Tool, ToolInput}

/**
 * Runtime request to a provider. Not serializable — `tools` carry executable
 * behavior. If/when replay or caching is needed, extract a snapshot with just
 * the serializable fields.
 */
case class ProviderRequest(conversationId: Id[Conversation],
                           modelId: Id[Model],
                           instructions: Instructions,
                           events: Vector[Event],
                           currentMode: Mode,
                           generationSettings: GenerationSettings,
                           tools: Vector[Tool[? <: ToolInput]] = Vector.empty,
                           requestId: Id[ProviderRequest] = Id())
