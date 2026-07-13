package sigil.tool

import lightdb.id.Id
import lightdb.time.Timestamp
import sigil.CancellationToken
import sigil.conversation.Conversation
import sigil.event.Event

/**
 * Live-registry entry for a DETACHED tool execution — a
 * [[Tool.detachable]] tool whose run outlived the detach threshold, so
 * its invoke settled with a tracking handle and the work continues on
 * a background fiber after the turn ended.
 *
 * In-memory only (the fiber cannot survive a restart); the durable
 * marker is the invoke row's `detached = true` flag, which restart
 * reconciliation compares against this registry to settle tasks lost
 * with the process. The invoke id doubles as the task handle
 * everywhere the task is referenced — frames, the task panel, logs.
 *
 * `cancellation` is the token the tool's `ctx.checkpoint` observes for
 * the WHOLE execution (attached and detached phases); a conversation
 * Stop cancels it unless the tool set
 * [[Tool.detachedKeepRunningOnStop]].
 */
final case class DetachedToolTask(invokeId: Id[Event],
                                  conversationId: Id[Conversation],
                                  toolName: ToolName,
                                  workspace: Option[String],
                                  keepRunningOnStop: Boolean,
                                  cancellation: CancellationToken,
                                  startedAt: Timestamp,
                                  /** `None` while the dispatch is still ATTACHED
                                    * (registered at dispatch so a Stop can reach
                                    * the token in either phase); set when the
                                    * threshold promotes it. Only detached
                                    * entries appear in the task panel. */
                                  detachedAt: Option[Timestamp])
