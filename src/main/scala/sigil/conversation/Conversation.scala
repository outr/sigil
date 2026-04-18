package sigil.conversation

import lightdb.id.Id
import rapid.Unique

trait Conversation {
  def id: Id[Conversation]
}

object Conversation {
  def id(value: String = Unique()): Id[Conversation] = Id(value)
}