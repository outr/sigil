package sigil.tool.discovery

import fabric.rw.*

/**
 * Discriminator for what kind of capability a [[CapabilityMatch]]
 * represents. Used by `find_capability` to surface heterogeneous
 * matches (a Tool the agent can call directly, a Mode the agent must
 * `change_mode` into, a Skill that overlays the system prompt) under
 * a single result type. Bug #66.
 *
 * Mirrors the Scalagentic shape the framework's predecessor used.
 * The enum is open at the conceptual layer (apps can grow new
 * categories — `McpServer`, `Agent`, etc.) but for now the framework
 * ships only the three categories first-class to Sigil.
 */
enum CapabilityType derives RW {
  /** A tool the agent can invoke directly on its next turn — the
    * traditional `find_capability` result. */
  case Tool

  /** A behavioral mode bundling a focused skill + scoped tool roster.
    * Mode-gated tools (per #59's `ScriptAuthoringMode` pattern) are
    * invisible to the conversation-mode tool roster; surfacing the
    * mode here gives the agent the entry point it needs. */
  case Mode

  /** A standalone skill — system-prompt overlay for specialised
    * tasks. Skills can ship attached to a Mode (auto-activate on
    * mode entry) or as free-floating records that an agent
    * activates explicitly via `activate_skill` after discovery. */
  case Skill

  /** A persisted [[sigil.conversation.ContextMemory]] — a fact the
    * agent can pull into context via `lookup`. Discovery returns
    * `name` (memory key) + `summary`; `lookup` retrieves the full
    * `fact`. Memory matches do not auto-load — the agent decides
    * whether the fact is worth the token cost. */
  case Memory

  /** A persisted [[sigil.information.Information]] record — large
    * referenced content the agent retrieves via `lookup` to get
    * the full body behind a catalog entry's summary. */
  case Information
}
