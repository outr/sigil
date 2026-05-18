package sigil.service

import fabric.rw.*

/**
 * Severity for a [[sigil.signal.ServiceLogSignal]] line. Mirrors the
 * common five-level taxonomy so consumers can filter / colour log
 * tails by importance without needing per-service custom level
 * vocabularies.
 */
enum ServiceLogLevel derives RW {
  case Trace, Debug, Info, Warn, Error
}
