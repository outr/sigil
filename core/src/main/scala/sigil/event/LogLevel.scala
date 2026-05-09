package sigil.event

import fabric.rw.RW

/**
 * Severity for a [[ToolLog]] line. Consumers (chat-list per-tool
 * chip, audit dashboards) use this to tint the rendering — info as
 * default neutral, warn / error highlighted, debug muted.
 *
 * The framework assigns no semantics beyond "tools choose a level
 * when they emit". Apps may filter (e.g. only render warn+ in
 * production UIs) or persist all levels for replay.
 */
enum LogLevel derives RW {
  case Debug, Info, Warn, Error
}
