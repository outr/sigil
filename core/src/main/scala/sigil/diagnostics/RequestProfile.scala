package sigil.diagnostics

import fabric.rw.*
import lightdb.id.Id
import sigil.event.Event

/**
 * Per-section token-budget breakdown of a single
 * [[sigil.provider.ConversationRequest]] as it would land on the wire.
 *
 * `sections` carries the system-prompt sections plus the wire-level
 * pieces (frames, tool roster). `total` is the sum across all sections.
 * `frames` carries an optional per-frame breakdown for digging into
 * which conversation events are dominant.
 *
 * Used by the Phase 0 profiling instrumentation that drives the
 * shedding-policy design — see `benchmark/src/main/scala/bench/contextprofile/`.
 */
case class RequestProfile(total: Int,
                          sections: Map[ProfileSection, Int],
                          frames: Vector[FrameProfile],
                          insights: List[ContextManagementInsight] = Nil) derives RW

/** Discriminator for the parts of a wire request a `RequestProfile`
  * counts. Mirrors the section layout `Provider.renderSystem`
  * produces, plus the framing pieces (frames, tool roster) that live
  * outside the system prompt on the wire. */
enum ProfileSection derives RW {
  case ToolFramingPrefix
  case ModeBlock
  case Instructions
  case CriticalMemories
  case Summaries
  case Memories
  case Information
  case Roles
  case ActiveSkills
  case RecentTools
  case SuggestedTools
  case ExtraContext
  case Frames
  case ToolRoster
}

/** Per-frame token contribution. `kind` is one of `Text`, `ToolCall`,
  * `ToolResult`, `System`, `Reasoning` so reports can group by event type. */
case class FrameProfile(kind: String,
                        sourceEventId: Id[Event],
                        tokens: Int) derives RW
