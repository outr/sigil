package sigil.event

import lightdb.id.Id
import lightdb.time.Timestamp
import fabric.*
import fabric.define.Definition
import fabric.rw.*

trait Event {
  def id: Id[Event]
  def visibility: Set[EventVisibility]
  def timestamp: Timestamp
}

object Event {
  private var types: List[RW[? <: Event]] = Nil
  private var _poly: RW[Event] = generate()

  private def generate(): RW[Event] = RW.poly[Event]()(types *)

  /** Register additional Event subtypes into the poly RW.
   * Call this at backend startup BEFORE any serialization occurs. */
  def register(types: RW[? <: Event]*): Unit = synchronized {
    this.types = this.types ++ types.toList
    _poly = generate()
  }

  given RW[Event] = new RW[Event] {
    override def read(t: Event): Json = _poly.read(t)

    override def write(json: Json): Event = _poly.write(json)

    override def definition: Definition = _poly.definition
  }

  /** Generate a new event ID. */
  def id(): Id[Event] = Id[Event]()
}