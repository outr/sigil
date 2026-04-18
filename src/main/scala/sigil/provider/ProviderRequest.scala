package sigil.provider

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.Event

case class ProviderRequest(conversationId: Id[Conversation],
                           modelId: Id[Model],
                           instructions: Instructions,
                           events: Vector[Event],
                           currentMode: Mode,
                           generationSettings: GenerationSettings,
                           requestId: Id[ProviderRequest] = Id()) derives RW
