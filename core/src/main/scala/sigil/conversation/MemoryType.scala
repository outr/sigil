package sigil.conversation

import fabric.rw.*
import sigil.PolyType

/**
 * Taxonomy of a [[ContextMemory]]. Same polymorphic pattern as
 * [[SpaceId]] — Sigil ships common defaults (Fact, Decision,
 * Preference, ActionItem, Other) and apps can register domain-specific
 * types (e.g. `Medical`, `Financial`, `Code`) by extending the trait and
 * registering their RW via `MemoryType.register(...)` at startup.
 *
 * Rendered by helpers/tools; the framework itself makes no behavioral
 * decisions based on the type — apps that want type-specific retention,
 * routing, or prioritization can dispatch in their curator/extractor.
 */
trait MemoryType {
  def value: String
}

object MemoryType extends PolyType[MemoryType] {
  case object Fact extends MemoryType       { override val value: String = "fact" }
  case object Decision extends MemoryType   { override val value: String = "decision" }
  case object Preference extends MemoryType { override val value: String = "preference" }
  case object ActionItem extends MemoryType { override val value: String = "action_item" }
  /** Agent-authored short observation — like a developer's notebook
    * entry. Same retrieval surface as other memories (space-scoped,
    * embedded for semantic search) but flagged so apps that want to
    * surface notes separately in a UI can filter on the type. */
  case object Note extends MemoryType       { override val value: String = "note" }
  case object Other extends MemoryType      { override val value: String = "other" }

  /** The built-in variants Sigil ships. Apps can register more. */
  val defaults: Set[MemoryType] = Set(Fact, Decision, Preference, ActionItem, Note, Other)

  // Register the built-ins with the poly RW so they round-trip without
  // the app having to call `MemoryType.register(...)` first. Case-object
  // RWs come from `RW.static` (fabric's singleton path), matching the
  // way `SpaceId` subtypes are registered in TestSigil.
  register(
    RW.static(Fact),
    RW.static(Decision),
    RW.static(Preference),
    RW.static(ActionItem),
    RW.static(Note),
    RW.static(Other)
  )
}
