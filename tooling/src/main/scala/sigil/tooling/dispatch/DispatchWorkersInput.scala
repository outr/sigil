package sigil.tooling.dispatch

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.provider.Complexity
import sigil.role.Role
import sigil.tool.ToolInput
import sigil.tool.output.ToolOutputNode

/**
 * Input for the refactored [[DispatchWorkersTool]] (sigil #288).
 *
 * `dispatch_workers` is now "fan out N `delegate_task` calls, one
 * per item in a paginated container." Each spawned worker is a
 * full `SigilAgentDecisionStep` worker with `delegate_task`'s
 * contract — async return, `ask_parent` supported, observable on
 * the wire, tool whitelist, mediator pattern via the parent.
 *
 * The old `action: String` script and `confirmed: Boolean`
 * two-call protocol are gone. Per-item judgment now lives inside
 * each worker (which can read its item, reason about it, call its
 * tools, and emit a final summary). The pre-flight "preview before
 * commit" surface is replaced by the worker itself making decisions
 * per item.
 *
 *   - `itemsId` — id of a container holding the worker items. Same
 *     producers as before (any paginated tool's `callId`,
 *     `create_container`, `load_file_as_container`, `filter_container`).
 *   - `workerPrompt` — the per-worker user prompt. The framework
 *     prepends the per-item payload before the prompt, so the
 *     worker sees both. Plain text — write as you'd write any
 *     `delegate_task` brief.
 *   - `goal` — one-sentence intent for the whole dispatch
 *     ("refactor each file to use the new API"). Surfaced separately
 *     for forensics; available to the worker as additional context.
 *   - `role` — the worker's identity (description, optional skill,
 *     [[sigil.provider.WorkType]]). Reused across every spawned
 *     worker; per-item variance lives in the item payload + the
 *     prompt template, not in the role.
 *   - `complexity` — optional routing hint passed through to each
 *     worker's model resolution (see [[sigil.Sigil.routedModelFor]]).
 *   - `modelId` — optional explicit model id; when set, every
 *     worker uses this model. When unset, the framework resolves
 *     per the strategy + `complexity` hint.
 *   - `toolNames` — the worker's tool roster. Empty inherits the
 *     spawning agent's effective roster (same default as
 *     `delegate_task`); explicit list restricts.
 *   - `maxIterations` — caps each worker's agent loop.
 *   - `itemsAt` — tree level to dispatch over (default 0 =
 *     top-level).
 *   - `itemsLimit` — cap on the count consumed (default 50;
 *     protection against accidentally dispatching against a 10K-item
 *     container).
 *   - `maxParallel` — concurrency cap (default 5). At most N workers
 *     are running concurrently; as one settles, the next from the
 *     queue starts. Lazy creation — worker conversations are only
 *     created when capacity frees.
 *   - `conversationId` — when set, reads `itemsId` from the
 *     specified conversation (typically a worker conversation's
 *     paginated output). Gated by
 *     [[sigil.Sigil.canReadConversation]]. When unset, defaults to
 *     the caller's current conversation.
 */
case class DispatchWorkersInput(itemsId: Id[ToolOutputNode],
                                workerPrompt: String,
                                role: Role,
                                goal: Option[String] = None,
                                complexity: Option[Complexity] = None,
                                modelId: Option[String] = None,
                                toolNames: List[String] = Nil,
                                maxIterations: Option[Int] = None,
                                itemsAt: Option[Int] = None,
                                itemsLimit: Int = 50,
                                maxParallel: Int = 5,
                                conversationId: Option[Id[Conversation]] = None)
  extends ToolInput derives RW
