package sigil.event

import fabric.rw.RW

enum EventVisibility derives RW {
  case UI
  case Model
}
