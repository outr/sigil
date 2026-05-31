package sigil.tooling.dispatch

import fabric.Json
import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id as LId
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.db.Model
import sigil.event.{Message, MessageRole}
import sigil.participant.{DefaultAgentParticipant, ParticipantId, WorkerParticipantId}
import sigil.provider.ToolPolicy
import sigil.signal.{AgentActivity, AgentStateDelta, EventState}
import sigil.tool.model.ResponseContent
import sigil.tool.{Tool, ToolContext, ToolExample, ToolName, ToolResult}
import sigil.tooling.container.ContainerSupport

/**
 * Generic parallel per-item dispatch — the *headless* sibling of the
 * supervised [[sigil.tool.util.DelegateTaskTool delegate_task]] bridge
 * (sigil #327). Fans out N worker agents over a container of items, one
 * worker per item, all sharing the same role + worker prompt.
 *
 * Each item spawns one worker as a real [[DefaultAgentParticipant]] in
 * its own sub-conversation (linked to the parent), with the per-item
 * brief posted addressed to the worker. The worker runs canonical agent
 * turns — reasons about its item, calls its role-scoped tools, and
 * responds with a result — then settles to `Idle`. There is no
 * supervisor co-resident: fan-out workers are autonomous and
 * terminal-reporting (unlike the supervised single-worker bridge).
 *
 * Async lifecycle:
 *   1. Tool returns immediately with [[DispatchWorkersOutput]] (the
 *      handle: `dispatchId`, `total`, `workersStarted`); a
 *      [[DispatchStarted]] event lands in the parent conversation.
 *   2. The first `min(total, maxParallel)` workers spawn; the rest queue.
 *   3. As each worker settles (its `AgentState` goes Idle), a per-dispatch
 *      background coordinator records the worker's result, advances the
 *      queue (keeping at most `maxParallel` in flight), and once every
 *      item has settled emits [[DispatchCompleted]] into the parent
 *      conversation — which wakes the parent agent's loop.
 *
 * Pure conversations + agents: no workflow runtime required. The MVP
 * coordinator is in-process — a restart mid-dispatch loses the in-flight
 * aggregation (the worker conversations persist and their agents settle,
 * but no `DispatchCompleted` fires for the partial set). Durable
 * coordinator state is a follow-on.
 */
final class DispatchWorkersTool extends Tool {
  type Input  = DispatchWorkersInput
  type Output = DispatchWorkersOutput
  val inputRW  = summon[RW[DispatchWorkersInput]]
  val outputRW = summon[RW[DispatchWorkersOutput]]

  val name = ToolName("dispatch_workers")
  val description =
    """Fan out N worker agents over a container of items — one worker per item, all sharing
      |the same role + worker prompt. Each worker reads its item, reasons about it, calls its
      |tools, and responds with a final result, then settles. Workers are autonomous (no
      |supervisor) and report their results back when they finish.
      |
      |Returns immediately with a dispatch handle. The aggregated per-worker results land later
      |via a DispatchCompleted event in this conversation that triggers your next iteration — you
      |don't need to poll or wait. The first iteration after the dispatch settles sees the full
      |aggregated outcome (per-worker status + summary + worker conversation id for drill-down).
      |
      |Required:
      |  - `itemsId`      — container id (from any paginated tool's first-page result, or from
      |                     a container-producing tool such as the ones in your roster)
      |  - `workerPrompt` — per-worker user prompt; the item payload is prepended automatically
      |  - `role`         — the worker's identity + workType
      |
      |Optional:
      |  - `goal`           — one-sentence intent for the dispatch, for forensics
      |  - `complexity`     — routing hint passed to each worker's model resolution
      |  - `modelId`        — explicit model override per worker
      |  - `toolNames`      — worker roster (empty gives the worker just the framework essentials)
      |  - `itemsAt`        — container tree level to read from
      |  - `itemsLimit`     — hard cap on items consumed
      |  - `maxParallel`    — concurrency cap (default 5; at most N workers run at once)
      |  - `conversationId` — when reading items from another conversation (e.g. a worker's
      |                       output), gated by the cross-conversation read predicate
      |
      |Use for: refactor work across many files, per-item classification, summarization, multi-
      |target investigation. Don't use for deterministic transforms — those compose better via
      |parallel tool calls (emit N tool calls in one turn) or a script tool.""".stripMargin

  override val keywords = Set(
    "dispatch", "workers", "parallel", "fanout", "fan out", "per-item", "per item",
    "refactor", "rewrite", "modify", "multi-file", "across files",
    "find", "replace", "find and replace", "search and replace",
    "bulk", "batch", "loop", "map", "delegate", "subagent"
  )

  override val examples: List[ToolExample] = Nil

  override def executeResult(input: DispatchWorkersInput,
                             ctx: ToolContext): Task[ToolResult[DispatchWorkersOutput]] = {
    val host          = ctx.sigil
    val targetConvId  = input.conversationId.getOrElse(ctx.conversation.id)
    val currentConvId = ctx.conversation.id
    host.canReadConversation(currentConvId, targetConvId).flatMap {
      case Left(reason) =>
        Task.pure(ToolResult.failure(
          message = s"dispatch_workers: cannot read items from conversation `${targetConvId.value}` — $reason",
          hint = Some(
            "Cross-conversation item reads are allowed only against the caller's own conversation, " +
              "its parent, or one of its workers."
          )
        ))
      case Right(_) =>
        ContainerSupport.resolveItems(
          ctx = ctx,
          itemsId = input.itemsId,
          itemsAt = input.itemsAt,
          itemsLimit = Some(input.itemsLimit)
        ).flatMap { items =>
          if (items.isEmpty) {
            Task.pure(ToolResult.Success(DispatchWorkersOutput(
              dispatchId     = rapid.Unique(),
              total          = 0,
              workersStarted = 0,
              abortReason    = Some(
                s"dispatch_workers: container `${input.itemsId.value}` resolved to 0 items at level " +
                  s"${input.itemsAt.getOrElse(0)} (limit ${input.itemsLimit}). Verify the container has " +
                  "items at the requested level."
              )
            )))
          } else startDispatch(host, input, ctx, items)
        }
    }
  }

  private def startDispatch(host: Sigil,
                            input: DispatchWorkersInput,
                            ctx: ToolContext,
                            items: List[Json]): Task[ToolResult[DispatchWorkersOutput]] = {
    val dispatchId    = rapid.Unique()
    val total         = items.size
    val cap           = math.max(1, input.maxParallel)
    val initial       = math.min(total, cap)
    val parentConvId  = ctx.conversation.id
    val parentTopicId = ctx.conversation.currentTopicId

    val state = new DispatchState(
      dispatchId    = dispatchId,
      parentConvId  = parentConvId,
      parentTopicId = parentTopicId,
      parentCaller  = ctx.caller,
      fallbackModel = ctx.modelId,
      items         = items,
      maxParallel   = cap,
      input         = input,
      host          = host
    )

    for {
      _ <- host.publish(DispatchStarted(
        participantId   = ctx.caller,
        conversationId  = parentConvId,
        topicId         = parentTopicId,
        dispatchId      = dispatchId,
        total           = total,
        workersStarted  = initial,
        maxParallel     = cap
      ))
      // Start the settle-watcher BEFORE spawning so a fast worker's Idle
      // isn't missed by a late subscription.
      _ = state.startCoordinator()
      _ <- Task.sequence((0 until initial).toList.map(idx => state.spawnAt(idx)))
    } yield ToolResult.Success(DispatchWorkersOutput(
      dispatchId     = dispatchId,
      total          = total,
      workersStarted = initial
    ))
  }
}

object DispatchWorkersTool {

  private val PreviewLength: Int = 80
  private[dispatch] def previewOf(item: Json): String = {
    val rendered = JsonFormatter.Compact(item)
    if (rendered.length <= PreviewLength) rendered
    else rendered.take(PreviewLength - 3) + "..."
  }

  /** Compose the worker's brief: optional dispatch goal first, then
    * per-item payload, then the agent's `workerPrompt` template. */
  private[dispatch] def composeBrief(input: DispatchWorkersInput, item: Json): String = {
    val payloadBlock = s"Item:\n${JsonFormatter.Default(item)}"
    val goalBlock    = input.goal.filter(_.nonEmpty).map(g => s"Dispatch goal: $g\n\n").getOrElse("")
    s"$goalBlock$payloadBlock\n\n${input.workerPrompt}"
  }
}

/** Per-dispatch coordinator state. Tracks per-item assignment
  * (workerConvId → itemIndex), the worker agent id per conversation,
  * inflight spawn cursor, and accumulated results. As each worker settles
  * to Idle, the coordinator reads its result from the worker
  * conversation, records the per-worker summary, advances the spawn
  * queue, and once every item has settled emits [[DispatchCompleted]]
  * into the parent conversation. */
private[dispatch] final class DispatchState(val dispatchId: String,
                                            val parentConvId: LId[Conversation],
                                            val parentTopicId: LId[sigil.conversation.Topic],
                                            val parentCaller: ParticipantId,
                                            val fallbackModel: LId[Model],
                                            val items: List[Json],
                                            val maxParallel: Int,
                                            val input: DispatchWorkersInput,
                                            val host: Sigil) {

  private val total = items.size
  private var nextItemToSpawn: Int = 0
  private val workerToIndex = scala.collection.mutable.Map.empty[LId[Conversation], Int]
  private val workerIds     = scala.collection.mutable.Map.empty[LId[Conversation], WorkerParticipantId]
  private val results       = scala.collection.mutable.LinkedHashMap.empty[Int, WorkerSummary]

  /** Spawn the worker for `itemIndex` as a real agent in its own
    * sub-conversation and post the brief addressed to it. Registers the
    * (workerConvId → itemIndex) mapping so the coordinator can match the
    * worker's Idle signal back to its item. */
  def spawnAt(itemIndex: Int): Task[Unit] = {
    val item        = items(itemIndex)
    val brief       = DispatchWorkersTool.composeBrief(input, item)
    val workerLabel = s"DispatchWorker[$dispatchId:$itemIndex] (${input.role.name})"

    val resolvedModelTask: Task[LId[Model]] = input.modelId match {
      case Some(explicit) =>
        Task.pure(host.cache.findTolerant(LId[Model](explicit.toLowerCase)).map(_._id).getOrElse(LId[Model](explicit)))
      case None =>
        host.routedModelFor(
          workType   = input.role.workType,
          chain      = List(parentCaller),
          fallback   = fallbackModel,
          complexity = input.complexity
        )
    }

    for {
      resolvedModel <- resolvedModelTask
      workerId = WorkerParticipantId(s"dispatch-$dispatchId-$itemIndex-${rapid.Unique()}")
      workerAgent = DefaultAgentParticipant(
        id        = workerId,
        modelId   = resolvedModel,
        toolNames = input.toolNames.map(ToolName(_)),
        tools     = ToolPolicy.Standard,
        workType  = input.role.workType,
        roles     = List(input.role)
      )
      _ = synchronized { nextItemToSpawn = math.max(nextItemToSpawn, itemIndex + 1) }
      workerConv <- host.newConversation(
        createdBy            = parentCaller,
        label                = workerLabel,
        summary              = input.goal.getOrElse(input.workerPrompt).take(80),
        participants         = List(workerAgent),
        parentConversationId = Some(parentConvId)
      )
      _ = synchronized {
        workerToIndex(workerConv._id) = itemIndex
        workerIds(workerConv._id) = workerId
      }
      _ <- host.publish(Message(
        participantId  = parentCaller,
        conversationId = workerConv._id,
        topicId        = workerConv.currentTopicId,
        content        = Vector(ResponseContent.Text(brief)),
        state          = EventState.Complete,
        role           = MessageRole.Standard,
        addressees     = Some(Set(workerId))
      ))
    } yield ()
  }

  /** Background fiber: watches for any agent settling to Idle, routes the
    * ones in this dispatch's worker conversations to [[handleWorkerIdle]],
    * and stops once every item has settled. */
  def startCoordinator(): Unit = {
    host.signals
      .collect { case d: AgentStateDelta if d.activity.contains(AgentActivity.Idle) => d.conversationId }
      .evalMap(handleWorkerIdle)
      .takeWhile(_ => !isComplete)
      .drain
      .startUnit()
  }

  private def isComplete: Boolean = synchronized { results.size >= total }

  /** A worker conversation settled to Idle — read its result, record the
    * per-worker summary, advance the spawn queue, and finalize when the
    * whole dispatch is done. Dedup-guarded so a worker that briefly
    * re-enters Idle isn't double-counted. */
  private def handleWorkerIdle(convId: LId[Conversation]): Task[Unit] = {
    val (idxOpt, workerIdOpt) = synchronized {
      (workerToIndex.get(convId), workerIds.get(convId))
    }
    idxOpt match {
      case None => Task.unit  // not one of mine, or already recorded
      case Some(idx) =>
        host.withDB(_.eventsTransaction(convId)(_.list)).flatMap { evs =>
          val workerMsgs = evs.collect {
            case m: Message if workerIdOpt.contains(m.participantId) => m
          }
          val lastMsg     = workerMsgs.lastOption
          val summaryText = lastMsg.map(_.content.collect { case ResponseContent.Text(t) => t }.mkString).filter(_.nonEmpty)
          val isFailure   = lastMsg.exists(_.isFailure)

          val (recorded, allDone, nextSpawn): (Boolean, Boolean, Option[Int]) = synchronized {
            if (!workerToIndex.contains(convId)) (false, false, None)
            else {
              workerToIndex.remove(convId)
              workerIds.remove(convId)
              results(idx) = WorkerSummary(
                itemIndex            = idx,
                itemPreview          = DispatchWorkersTool.previewOf(items(idx)),
                workerConversationId = convId.value,
                status               = if (isFailure) "Failure" else "Success",
                summary              = summaryText,
                iterations           = workerMsgs.size,
                exhausted            = isFailure
              )
              val toSpawn = if (nextItemToSpawn < total) Some(nextItemToSpawn) else None
              (true, results.size >= total, toSpawn)
            }
          }
          if (!recorded) Task.unit
          else {
            val spawnNext: Task[Unit] = nextSpawn match {
              case Some(i) => spawnAt(i).handleError(e => Task(scribe.warn(s"dispatch $dispatchId: spawn idx=$i failed: ${e.getMessage}")))
              case None    => Task.unit
            }
            val finalize: Task[Unit] = if (allDone) emitCompleted() else Task.unit
            spawnNext.flatMap(_ => finalize)
          }
        }
    }
  }

  private def emitCompleted(): Task[Unit] = {
    val workers   = synchronized(results.values.toList.sortBy(_.itemIndex))
    val succeeded = workers.count(_.status == "Success")
    val failed    = workers.size - succeeded
    host.publish(DispatchCompleted(
      participantId  = parentCaller,
      conversationId = parentConvId,
      topicId        = parentTopicId,
      dispatchId     = dispatchId,
      total          = total,
      succeeded      = succeeded,
      failed         = failed,
      workers        = workers
    )).map(_ => ())
  }
}
