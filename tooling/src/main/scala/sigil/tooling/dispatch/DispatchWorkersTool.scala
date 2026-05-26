package sigil.tooling.dispatch

import fabric.Json
import fabric.io.JsonFormatter
import fabric.rw.*
import lightdb.id.Id as LId
import rapid.{Stream, Task}
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.participant.ParticipantId
import sigil.signal.Signal
import sigil.tool.ToolContext
import sigil.tool.{Tool, ToolExample, ToolName, ToolResult}
import sigil.tooling.container.ContainerSupport
import sigil.workflow.event.TaskExecuted
import sigil.workflow.{AgentDecisionStepInput, WorkflowSigil, WorkflowStepInputCompiler}
import sigil.workflow.SigilWorkflowModel.stepRW
import strider.WorkflowParent

import java.util.concurrent.ConcurrentHashMap

/**
 * Generic parallel per-item dispatch. Sigil #288 — replaces the
 * prior compiled-Scala-action shape with "fan out N
 * [[sigil.tool.util.DelegateTaskTool delegate_task]] calls, one
 * per item in a paginated container."
 *
 * Each item spawns one worker (a [[sigil.workflow.SigilAgentDecisionStep]])
 * whose brief is the `workerPrompt` template prepended with the
 * item payload. Per-item judgment now lives inside each worker,
 * which can reason about its item, call tools (per the inherited
 * roster), ask the parent via `ask_parent`, and emit a final
 * `Complete:` summary.
 *
 * Async lifecycle:
 *   1. Tool returns immediately with [[DispatchWorkersOutput]] —
 *      the dispatch handle (`dispatchId`, `total`, `workersStarted`).
 *      A [[DispatchStarted]] event lands in the parent conversation.
 *   2. The first `min(total, maxParallel)` workers spawn; the rest
 *      queue.
 *   3. As workers settle (their [[TaskExecuted]] events arrive),
 *      a per-dispatch background coordinator advances the queue —
 *      maintaining at most `maxParallel` workers in flight at any
 *      moment.
 *   4. When every worker has settled, a [[DispatchCompleted]] event
 *      fires into the parent conversation with the aggregated
 *      per-worker [[WorkerSummary]]s. The parent agent's trigger
 *      filter wakes the loop; the next iteration reads the result
 *      and decides what to do next.
 *
 * Requires the host Sigil to mix in [[WorkflowSigil]] (each worker
 * is a strider workflow run). MVP coordinator is in-process — a
 * process restart mid-dispatch loses the in-flight aggregation
 * (the underlying worker workflows continue, but no
 * `DispatchCompleted` fires for the partial set). Durable
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
      |tools, and emits a final summary.
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
      |  - `toolNames`      — worker roster (empty inherits yours)
      |  - `maxIterations`  — per-worker cap
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
                             ctx: ToolContext): Task[ToolResult[DispatchWorkersOutput]] =
    ctx.sigil match {
      case ws: WorkflowSigil => kickoff(ws, input, ctx)
      case _ =>
        Task.pure(ToolResult.failure(
          "dispatch_workers requires the host Sigil to mix in WorkflowSigil; the workflow runtime is not active."
        ))
    }

  private def kickoff(ws: WorkflowSigil & Sigil,
                      input: DispatchWorkersInput,
                      ctx: ToolContext): Task[ToolResult[DispatchWorkersOutput]] = {
    val targetConvId  = input.conversationId.getOrElse(ctx.conversation.id)
    val currentConvId = ctx.conversation.id
    ws.canReadConversation(currentConvId, targetConvId).flatMap {
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
            val dispatchId = rapid.Unique()
            Task.pure(ToolResult.Success(DispatchWorkersOutput(
              dispatchId     = dispatchId,
              total          = 0,
              workersStarted = 0,
              abortReason    = Some(
                s"dispatch_workers: container `${input.itemsId.value}` resolved to 0 items at level " +
                  s"${input.itemsAt.getOrElse(0)} (limit ${input.itemsLimit}). Verify the container has " +
                  "items at the requested level."
              )
            )))
          } else startDispatch(ws, input, ctx, items)
        }
    }
  }

  private def startDispatch(ws: WorkflowSigil & Sigil,
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
      items         = items,
      maxParallel   = cap,
      input         = input,
      ws            = ws
    )
    DispatchWorkersTool.coordinator.register(state)

    for {
      _ <- ws.publish(DispatchStarted(
        participantId   = ctx.caller,
        conversationId  = parentConvId,
        topicId         = parentTopicId,
        dispatchId      = dispatchId,
        total           = total,
        workersStarted  = initial,
        maxParallel     = cap
      ))
      _ <- Task.sequence((0 until initial).toList.map(idx => state.spawnAt(idx)))
      _ = state.startCoordinator()
    } yield ToolResult.Success(DispatchWorkersOutput(
      dispatchId     = dispatchId,
      total          = total,
      workersStarted = initial
    ))
  }
}

object DispatchWorkersTool {

  /** Process-wide coordinator registry. Each active dispatch
    * registers its [[DispatchState]] here; the coordinator's
    * background fiber drains TaskExecuted signals and looks up the
    * matching state by `workerConversationId`. */
  private[dispatch] val coordinator: DispatchRegistry = new DispatchRegistry

  private val PreviewLength: Int = 80
  private[dispatch] def previewOf(item: Json): String = {
    val rendered = JsonFormatter.Compact(item)
    if (rendered.length <= PreviewLength) rendered
    else rendered.take(PreviewLength - 3) + "..."
  }

  /** Compose the worker's brief: optional dispatch goal first,
    * then per-item payload, then the agent's `workerPrompt`
    * template. The payload sits between goal and prompt so the
    * worker reads them as a coherent block. */
  private[dispatch] def composeBrief(input: DispatchWorkersInput, item: Json): String = {
    val payloadBlock = s"Item:\n${JsonFormatter.Default(item)}"
    val goalBlock    = input.goal.filter(_.nonEmpty).map(g => s"Dispatch goal: $g\n\n").getOrElse("")
    s"$goalBlock$payloadBlock\n\n${input.workerPrompt}"
  }
}

/** In-process registry of active dispatches keyed by
  * `workerConversationId` so the coordinator can route incoming
  * TaskExecuted events to the right [[DispatchState]] in O(1). */
private[dispatch] final class DispatchRegistry {
  private val byWorker: ConcurrentHashMap[LId[Conversation], DispatchState] = new ConcurrentHashMap()

  def register(state: DispatchState): Unit = state.attach(this)

  def link(workerId: LId[Conversation], state: DispatchState): Unit = byWorker.put(workerId, state)
  def unlink(workerId: LId[Conversation]): Unit = byWorker.remove(workerId)
  def find(workerId: LId[Conversation]): Option[DispatchState] = Option(byWorker.get(workerId))
}

/** Per-dispatch coordinator state. Tracks per-item assignment
  * (workerConvId → itemIndex), inflight count, and accumulated
  * results. As each TaskExecuted arrives, the coordinator records
  * the per-worker summary, advances the queue, and once every
  * item has settled emits [[DispatchCompleted]] into the parent
  * conversation. */
private[dispatch] final class DispatchState(val dispatchId: String,
                                            val parentConvId: LId[Conversation],
                                            val parentTopicId: LId[sigil.conversation.Topic],
                                            val parentCaller: ParticipantId,
                                            val items: List[Json],
                                            val maxParallel: Int,
                                            val input: DispatchWorkersInput,
                                            val ws: WorkflowSigil & Sigil) {

  private val total = items.size
  private var nextItemToSpawn: Int = 0
  private val workerToIndex = scala.collection.mutable.Map.empty[LId[Conversation], Int]
  private val results       = scala.collection.mutable.LinkedHashMap.empty[Int, WorkerSummary]
  private var registry: DispatchRegistry = null

  def attach(r: DispatchRegistry): Unit = synchronized {
    registry = r
  }

  /** Spawn the worker for `itemIndex` and register the
    * (workerConvId → itemIndex) mapping. Caller sequences the
    * initial batch via Task chaining; coordinator launches
    * follow-on spawns one at a time as workers settle. */
  def spawnAt(itemIndex: Int): Task[Unit] = {
    val item        = items(itemIndex)
    val brief       = DispatchWorkersTool.composeBrief(input, item)
    val workerLabel = s"DispatchWorker[$dispatchId:$itemIndex] (${input.role.name})"

    val resolvedModelTask: Task[String] = input.modelId match {
      case Some(explicit) => Task.pure(explicit)
      case None =>
        ws.routedModelFor(
          workType   = input.role.workType,
          chain      = List(parentCaller),
          fallback   = ws.cache.all.headOption.map(_._id).getOrElse(
            lightdb.id.Id[sigil.db.Model]("dispatch-no-default-model")
          ),
          complexity = input.complexity
        ).map(_.value)
    }

    for {
      resolvedModel <- resolvedModelTask
      effectiveToolNames <- inheritParentRosterTask
      _ = synchronized { nextItemToSpawn = math.max(nextItemToSpawn, itemIndex + 1) }
      workerConv <- ws.newConversation(
        createdBy            = parentCaller,
        label                = workerLabel,
        summary              = input.goal.getOrElse(input.workerPrompt).take(80),
        participants         = Nil,
        parentConversationId = Some(parentConvId)
      )
      _ = synchronized {
        workerToIndex(workerConv._id) = itemIndex
        if (registry != null) registry.link(workerConv._id, this)
      }
      stepInput = AgentDecisionStepInput(
        id            = "decision-0",
        name          = Some(s"DispatchWorker:$itemIndex"),
        role          = input.role,
        brief         = brief,
        modelId       = resolvedModel,
        maxIterations = input.maxIterations.getOrElse(50),
        toolNames     = effectiveToolNames
      )
      compiled = WorkflowStepInputCompiler.compile(List(stepInput))(using summon[fabric.rw.RW[strider.step.Step]])
      sourceId = LId[WorkflowParent](s"dispatch-$dispatchId-item-$itemIndex-${rapid.Unique()}")
      _ <- ws.workflowManager.schedule(
        name           = workerLabel,
        steps          = compiled.steps,
        sourceId       = sourceId,
        conversationId = Some(workerConv._id.value)
      )
    } yield ()
  }

  /** Background fiber: drains [[TaskExecuted]] events filtered to
    * this dispatch's workers, records per-worker results, advances
    * the spawn queue, and emits [[DispatchCompleted]] when every
    * item has settled. */
  def startCoordinator(): Unit = {
    ws.signals
      .collect { case te: TaskExecuted => te }
      .evalMap(handleSettle)
      .takeWhile(_ => !isComplete)
      .drain
      .startUnit()
  }

  private def isComplete: Boolean = synchronized { results.size >= total }

  private def handleSettle(te: TaskExecuted): Task[Unit] = {
    te.workerConversationId match {
      case None => Task.unit
      case Some(workerConv) =>
        val (matchedIndexOpt, allDone, nextSpawn): (Option[Int], Boolean, Option[Int]) = synchronized {
          workerToIndex.remove(workerConv) match {
            case None => (None, false, None)
            case Some(idx) =>
              val status = if (te.exhausted) "Failure" else "Success"
              val item = items(idx)
              results(idx) = WorkerSummary(
                itemIndex            = idx,
                itemPreview          = DispatchWorkersTool.previewOf(item),
                workerConversationId = workerConv.value,
                status               = status,
                summary              = Option(te.summary).filter(_.nonEmpty),
                iterations           = te.iterations,
                exhausted            = te.exhausted
              )
              if (registry != null) registry.unlink(workerConv)
              val toSpawn =
                if (nextItemToSpawn < total) Some(nextItemToSpawn)
                else None
              (Some(idx), results.size >= total, toSpawn)
          }
        }
        if (matchedIndexOpt.isEmpty) Task.unit
        else {
          val spawnNext: Task[Unit] = nextSpawn match {
            case Some(idx) => spawnAt(idx).handleError(e => Task(scribe.warn(s"dispatch $dispatchId: spawn idx=$idx failed: ${e.getMessage}")))
            case None      => Task.unit
          }
          val finalize: Task[Unit] = if (allDone) emitCompleted() else Task.unit
          spawnNext.flatMap(_ => finalize)
        }
    }
  }

  private def emitCompleted(): Task[Unit] = {
    val workers = synchronized(results.values.toList.sortBy(_.itemIndex))
    val succeeded = workers.count(_.status == "Success")
    val failed    = workers.size - succeeded
    ws.publish(DispatchCompleted(
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

  /** Inherit the spawning agent's effective roster — same logic as
    * [[sigil.tool.util.DelegateTaskTool]]'s inheritParentRoster.
    * Loads the parent conversation, finds the calling agent in its
    * participants list, asks the framework for its effective roster
    * for the conversation's current mode. */
  private def inheritParentRosterTask: Task[List[String]] =
    if (input.toolNames.nonEmpty) Task.pure(input.toolNames)
    else ws.withDB(_.conversations.transaction(_.get(parentConvId))).map {
      case None       => Nil
      case Some(conv) =>
        conv.participants.collectFirst {
          case agent: sigil.participant.AgentParticipant if agent.id == parentCaller => agent
        }.map(agent => ws.effectiveToolNames(agent, conv.currentMode, Nil).map(_.value))
         .getOrElse(Nil)
    }
}
