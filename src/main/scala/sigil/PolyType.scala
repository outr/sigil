package sigil

import fabric.Json
import fabric.define.Definition
import fabric.rw.RW

import scala.reflect.ClassTag

trait PolyType[T: ClassTag] {
  private var types: List[RW[? <: T]] = Nil
  private var _poly: RW[T] = generate()

  private def generate(): RW[T] = RW.poly[T]()(types*)

  /**
   * Register additional T subtypes into the poly RW.
   * Call this at backend startup BEFORE any serialization occurs.
   */
  def register(types: RW[? <: T]*): Unit =
    synchronized {
      this.types = this.types ++ types.toList
      _poly = generate()
    }

  given RW[T] =
    new RW[T] {
      override def read(t: T): Json = _poly.read(t)

      override def write(json: Json): T = _poly.write(json)

      override def definition: Definition = _poly.definition
    }
}
