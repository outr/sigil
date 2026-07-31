package sigil.provider

import fabric.rw.*

/** How dependably a model emits well-formed tool calls. */
enum Reliability derives RW {
  case Solid
  case Wobbly
  case Unreliable
}
