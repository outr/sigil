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

  private def shortName(fullName: String): String = {
    val lastDot = fullName.lastIndexOf('.')
    val lastDollar = fullName.lastIndexOf('$')
    val start = math.max(lastDot, lastDollar) + 1
    fullName.substring(start)
  }

  /**
   * Namespace for typed-name construction and lookup for this poly.
   *
   *   - `name.of(instance)`            — from a concrete T-hierarchy instance
   *   - `name.of[S <: T]`              — from a subtype at compile time
   *   - `name.from(s)`                 — validated lookup against registered subtypes
   *   - `name.registered`              — the live set of registered names
   *
   * Avoids passing the `PolyType` as a reference — each PolyType's
   * `name.*` naturally scopes to its own registration.
   */
  object name {
    def of(instance: T): PolyName[T] = new PolyName[T](instance.getClass.getSimpleName)

    def of[S <: T](using ct: ClassTag[S]): PolyName[T] = new PolyName[T](ct.runtimeClass.getSimpleName)

    def from(n: String): Option[PolyName[T]] =
      if (registered.exists(_.name == n)) Some(new PolyName[T](n)) else None

    /**
     * Every subtype name currently registered into this poly. Derived
     * from each registered RW's `Definition.className`.
     */
    def registered: Set[PolyName[T]] = synchronized {
      types.flatMap(_.definition.className.map(n => new PolyName[T](shortName(n)))).toSet
    }
  }

  given RW[T] =
    new RW[T] {
      override def read(t: T): Json = _poly.read(t)

      override def write(json: Json): T = _poly.write(json)

      override def definition: Definition = _poly.definition
    }
}
