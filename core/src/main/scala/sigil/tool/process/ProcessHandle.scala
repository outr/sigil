package sigil.tool.process

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.conversation.Conversation

/**
 * Diagnostic record for a registered subprocess. `id` is the
 * caller-facing handle string; `pid` is the OS process id;
 * `conversationId` is the conversation that spawned the process
 * (used by `process_list` for the `current` scope filter).
 */
case class ProcessHandle(id: String,
                         pid: Long,
                         startedAt: Timestamp,
                         conversationId: Id[Conversation],
                         command: String)
  derives RW
