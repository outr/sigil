package sigil.event

import lightdb.id.Id
import lightdb.time.Timestamp
import fabric.*
import fabric.define.Definition
import fabric.rw.*
import sigil.PolyType

import scala.reflect.ClassTag

trait Event {
  def id: Id[Event]
  def visibility: Set[EventVisibility]
  def timestamp: Timestamp
}

object Event extends PolyType[Event] {
  /** Generate a new event ID. */
  def id(): Id[Event] = Id[Event]()
}