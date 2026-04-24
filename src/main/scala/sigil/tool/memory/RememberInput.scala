package sigil.tool.memory

import fabric.rw.*
import sigil.conversation.{MemorySpaceId, MemoryType}
import sigil.tool.ToolInput
import sigil.conversation.MemorySpaceId.given
import sigil.conversation.MemoryType.given

/**
 * Input for the `remember` tool. The agent calls this to persist a
 * durable fact into its memory store. Memories are keyed by
 * `(spaceId, key)`; storing the same `key` with identical `content`
 * refreshes metadata, while storing different `content` under the
 * same `key` versions the record (previous archived, new one
 * supersedes).
 *
 * `spaceId` is optional; when unset, the tool resolves a default via
 * the `Sigil.defaultMemorySpace(turnContext)` hook (usually a
 * per-user or per-conversation space).
 */
case class RememberInput(key: String,
                         label: String,
                         summary: String,
                         content: String,
                         tags: Vector[String] = Vector.empty,
                         memoryType: MemoryType = MemoryType.Fact,
                         spaceId: Option[MemorySpaceId] = None)
  extends ToolInput derives RW
