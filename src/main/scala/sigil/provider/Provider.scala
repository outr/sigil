package sigil.provider

import fabric.Json
import fabric.define.Definition
import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation
import sigil.db.Model

trait Provider {
  def `type`: ProviderType
  def models: List[Model]
}