package sigil.conversation

import lightdb.id.Id

trait Conversation {
  def id: Id[Conversation]
}
