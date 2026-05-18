package sigil.service

import fabric.rw.*

/**
 * Broad category for a [[Service]] — informs client chip rendering
 * (icon choice, grouping in the services panel) and lets apps filter
 * the registered surface by family. Open sum type: framework ships the
 * common categories, apps register additional concrete cases via the
 * [[ServiceKind.Other]] escape hatch when their service doesn't fit
 * one of the named kinds.
 *
 * Sealed trait (not enum) because [[Other]] carries a `label: String`
 * payload — Scala 3 enums forbid same-source-file extension which
 * makes app-defined kinds awkward. The `label` carried by `Other`
 * round-trips through fabric RW so a chip rendered from a wire
 * payload can show a meaningful category badge for app-specific
 * services without requiring the client to know every kind in advance.
 */
sealed trait ServiceKind derives RW

object ServiceKind {
  case object LanguageServer extends ServiceKind
  case object BuildServer extends ServiceKind
  case object ModelServer extends ServiceKind
  case object Storage extends ServiceKind
  case object Integration extends ServiceKind
  case class Other(label: String) extends ServiceKind
}
