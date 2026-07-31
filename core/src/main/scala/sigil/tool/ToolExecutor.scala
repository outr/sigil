package sigil.tool

import fabric.io.JsonFormatter
import lightdb.id.Id
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, Message, MessageDisposition, MessageRole, MessageVisibility, ToolOutcome}
import sigil.signal.{EventState, Signal, StateDelta, ToolDelta}
import sigil.tool.core.RespondFamilyTool
import sigil.tool.model.ResponseContent

/**
 * The single tool-dispatch pipeline — the ONLY code that unwraps a
 * [[Resolution]]. Every dispatch route (orchestrator wire path via
 * [[Tool.execute]], composition via [[Tool.invoke]], workflow steps
 * via [[executeCollected]]) runs the same stages:
 *
 *   gates (consent per [[GateContext]] → preconditions) → resolve →
 *   drain emitted events → bound output → exactly one paired result
 *   event.
 *
 * The detachable path is a *mode* of this executor (per
 * [[Execution.Detachable]] on the tool's profile), not a
 * re-implementation: the emit-buffer drain is
 * [[ToolContext.drainEmitted]] on every path, the buffer closes when
 * the resolution settles, and a late emit raises loudly.
 */
private[sigil] object ToolExecutor {

  // ---- Stream entry (orchestrator / Tool.execute) --------------------

  /** Full dispatch pipeline producing the `Stream[Signal]` surface the
    * orchestrator drives. Gate behavior follows `gate`; execution mode
    * (inline vs. detachable) follows the tool's profile. */
  def execute(tool: Tool,
              input: ToolInput,
              turn: TurnContext,
              invokeId: Id[Event],
              invokedName: ToolName,
              currentMessageId: Option[Id[Event]],
              gate: GateContext): Stream[Signal] = {
    def dispatch(): Stream[Signal] =
      if (tool.detachable) detachableStream(tool, input, turn, invokeId, invokedName, currentMessageId)
      else inlineStream(tool, input, turn, invokeId, invokedName, currentMessageId)

    val consentGated = gate match {
      case GateContext.Gated       => tool.requiresUserConsent
      case GateContext.PreGated(by) =>
        if (tool.requiresUserConsent)
          scribe.debug(s"Tool `${tool.name.value}`: consent gate pre-satisfied by parent invoke ${by.value}")
        false
    }

    // Fast path: tools without preconditions / an applicable consent
    // gate construct their stream synchronously — sync throws at
    // construction stay visible to the dispatch site's error handler
    // instead of sliding into an async stream error.
    if (tool.preconditions.isEmpty && !consentGated)
      dispatch()
    else Stream.force(consentOutcome(tool, turn, invokeId, consentGated).flatMap {
      case Left(blockedSignals) => Task.pure(Stream.emits(blockedSignals))
      case Right(()) =>
        if (tool.preconditions.isEmpty)
          Task.pure(dispatch())
        else preflightOutcome(tool, turn, invokeId).map {
          case Right(())            => dispatch()
          case Left(blockedSignals) => Stream.emits(blockedSignals)
        }
    })
  }

  // ---- Typed composition entry (Tool.invoke) -------------------------

  /** Composition dispatch — one tool's body invoking another. Runs
    * through the executor with [[GateContext.PreGated]]: consent is
    * skipped deliberately (and recorded), preconditions STILL run (an
    * unsatisfied one raises [[ToolFailureException]]). Emissions land
    * on the caller's still-open buffer and drain with the caller's own
    * result; a [[ToolResult.Failure]] raises [[ToolFailureException]];
    * a thrown error propagates as-is. */
  def invoke(tool: Tool)(input: tool.Input, context: ToolContext): Task[tool.Output] = {
    if (tool.requiresUserConsent)
      scribe.debug(s"Tool `${tool.name.value}`: consent gate pre-satisfied by parent invoke ${context.invokeId.value}")
    checkPreconditions(tool, context.turn).flatMap {
      case unsatisfied @ (_ :: _) =>
        val lines = unsatisfied.map { case (n, reason, fix) =>
          val fixHint = fix.map(f => s" — try `$f`").getOrElse("")
          s"$n: $reason$fixHint"
        }.mkString("; ")
        Task.error(new ToolFailureException(tool.name, s"preconditions not met: $lines", hint = None, args = None))
      case Nil =>
        tool.resolution match {
          case Resolution.Simple(run)   => run(input, context)
          case Resolution.Explicit(run) =>
            run(input, context).flatMap {
              case ToolResult.Success(value)           => Task.pure(value)
              case ToolResult.Failure(msg, hint, args) => Task.error(new ToolFailureException(tool.name, msg, hint, args))
            }
        }
    }
  }

  // ---- Typed collected entry (workflow steps) ------------------------

  /** Workflow-step dispatch: run the full gate pipeline (Gated — a
    * precondition-gated tool dispatched from a workflow is properly
    * blocked), resolve INLINE regardless of detachability (a step has
    * no turn to yield), and return both the stamped signal list and
    * the typed result envelope. */
  def executeCollected(tool: Tool)(input: tool.Input,
                                   turn: TurnContext,
                                   invokeId: Id[Event]): Task[(List[Signal], Option[ToolResultEnvelope[tool.Output]])] =
    consentOutcome(tool, turn, invokeId, consentGated = tool.requiresUserConsent).flatMap {
      case Left(blocked) => Task.pure((blocked, None))
      case Right(()) =>
        preflightOutcome(tool, turn, invokeId).flatMap {
          case Left(blocked) => Task.pure((blocked, None))
          case Right(()) =>
            val ctx = ToolContext(turn, invokeId, tool.name, None)
            resolveResult(tool)(input, ctx).flatMap { result =>
              Task(ctx.closeEmissions()).flatMap { _ =>
                settle(tool)(result, ctx).map { case (delta, envelope) =>
                  val signals = stamp(invokeId, tool.name)(ctx.drainEmitted().map(e => e: Signal) :+ (delta: Signal))
                  (signals, envelope)
                }
              }
            }
        }
    }

  // ---- Inline mode ---------------------------------------------------

  private def inlineStream(tool: Tool,
                           input: ToolInput,
                           turn: TurnContext,
                           invokeId: Id[Event],
                           invokedName: ToolName,
                           currentMessageId: Option[Id[Event]]): Stream[Signal] = {
    val ctx = ToolContext(turn, invokeId, invokedName, currentMessageId)
    Stream.force(
      resolveResult(tool)(input, ctx).flatMap { result =>
        ctx.closeEmissions()
        settle(tool)(result, ctx).map { case (delta, _) =>
          Stream.emits(stamp(invokeId, invokedName)(ctx.drainEmitted().map(e => e: Signal) :+ (delta: Signal)))
        }
      }
    )
  }

  // ---- Detachable mode -----------------------------------------------

  /** Dispatch mode for [[Execution.Detachable]] tools — runs the tool
    * body on its OWN fiber and polls completion up to
    * [[sigil.Sigil.toolDetachThresholdMs]]:
    *
    *   - **Completes in time** → emission-identical to the inline mode.
    *   - **Still running at the threshold** → DETACH: the invoke
    *     settles `Complete` with a tracking handle (`outcome` stays
    *     `Pending`, `detached = true`), the turn proceeds without it,
    *     and a completion watcher publishes the real settling delta
    *     plus a Tool-role continuation trigger when the work lands.
    *     `ctx.reportProgress` keeps flowing on the original invoke
    *     throughout.
    *
    * The execution observes a FRESH per-invoke [[sigil.CancellationToken]]
    * (registered on the host at dispatch) instead of the claim's — the
    * claim dies when the turn ends, and a conversation Stop must reach
    * the detached phase too ([[sigil.Sigil.applyStop]] cancels registry
    * tokens unless [[Tool.detachedKeepRunningOnStop]]). A cancelled
    * task settles its invoke with the Failure its checkpoint raised
    * and publishes NO continuation. */
  private def detachableStream(tool: Tool,
                               input: ToolInput,
                               turn: TurnContext,
                               invokeId: Id[Event],
                               invokedName: ToolName,
                               currentMessageId: Option[Id[Event]]): Stream[Signal] = {
    val sigil = turn.sigil
    val token = new _root_.sigil.CancellationToken(s"tool:${invokeId.value}")
    val toolCtx = ToolContext(
      turn = turn.copy(cancellation = Some(token)),
      invokeId = invokeId,
      toolName = invokedName,
      currentMessageId = currentMessageId
    )

    Stream.force(
      for {
        workspace <- sigil.resolvedWorkspaceFor(turn.conversation.id).handleError(_ => Task.pure(None))
        _ <- Task {
          sigil.registerDetachableDispatch(DetachedToolTask(
            invokeId          = invokeId,
            conversationId    = turn.conversation.id,
            toolName          = invokedName,
            workspace         = workspace.map(_.toString),
            keepRunningOnStop = tool.detachedKeepRunningOnStop,
            cancellation      = token,
            startedAt         = lightdb.time.Timestamp(lightdb.util.Nowish()),
            detachedAt        = None
          ))
        }
        completed = new java.util.concurrent.atomic.AtomicReference[Option[ToolResult[tool.Output]]](None)
        fiber <- resolveResult(tool)(input, toolCtx).map { r => completed.set(Some(r)); r }.start
        outcome <- awaitWithinThreshold(completed, sigil.toolDetachThresholdMs)
        signals <- outcome match {
          case Some(result) =>
            // Sub-threshold completion — emission-identical to inline.
            sigil.completeDetachedTool(invokeId)
            toolCtx.closeEmissions()
            settle(tool)(result, toolCtx).map { case (delta, _) =>
              stamp(invokeId, invokedName)(toolCtx.drainEmitted().map(e => e: Signal) :+ (delta: Signal))
            }
          case None =>
            // DETACH. Drain what the body has emitted so far; anything
            // emitted later publishes with the completion.
            val drainedNow = toolCtx.drainEmitted()
            sigil.markToolDetached(invokeId)
            val toolIsInternal = RespondFamilyTool.contains(invokedName)
            val detachDelta = ToolDelta(
              target         = invokeId,
              conversationId = turn.conversation.id,
              state          = Some(EventState.Complete),
              detached       = Some(true),
              internal       = toolIsInternal,
              summary        = Some(
                s"Detached: `${invokedName.value}` is still running in the background as task " +
                  s"${invokeId.value}. Progress continues on this call; the full result " +
                  "will arrive as a tool result when it completes. Do not re-issue this call."
              )
            )
            scribe.info(
              s"Tool `${invokedName.value}` promoted to detached task ${invokeId.value} " +
                s"(conversation ${turn.conversation.id.value}); the turn proceeds without it")
            // Completion watcher — publishes the real settle + the
            // continuation trigger outside the (long-gone) turn batch.
            fiber.join.flatMap { result =>
              toolCtx.closeEmissions()
              val lateEvents = toolCtx.drainEmitted()
              settle(tool)(result, toolCtx).flatMap { case (delta, _) =>
                val cancelled = token.isCancelled
                val publishLate = stamp(invokeId, invokedName)(lateEvents.map(e => e: Signal)).foldLeft(Task.unit) { (acc, sig) =>
                  acc.flatMap(_ => sigil.publish(sig).handleError(_ => Task.unit))
                }
                val publishSettle = sigil.publish(delta).handleError(t => Task {
                  scribe.error(s"Detached tool ${invokedName.value} (${invokeId.value}): settle publish failed", t)
                })
                val continuation =
                  if (cancelled) Task {
                    scribe.info(s"Detached task ${invokeId.value} (`${invokedName.value}`) settled after cancellation — no continuation")
                  }
                  else {
                    val summaryText = delta.summary.orElse(delta.output.flatMap {
                      case t: TextToolOutput => Some(t.text)
                      case _                 => None
                    }).getOrElse("(see the settled tool call for the result)")
                    val trigger = Message(
                      participantId  = turn.caller,
                      conversationId = turn.conversation.id,
                      topicId        = turn.conversation.currentTopicId,
                      role           = MessageRole.Tool,
                      content        = Vector(ResponseContent.Text(
                        s"Detached tool `${invokedName.value}` (task ${invokeId.value}) completed: $summaryText"
                      )),
                      state          = EventState.Complete,
                      visibility     = MessageVisibility.Agents,
                      origin         = Some(invokeId),
                      // Although agent-attributed (the agent made the call),
                      // this is EXTERNAL work arriving — the loop's
                      // own-emissions filter must not swallow it when the
                      // completion lands mid-turn.
                      source         = Some(_root_.sigil.orchestrator.Orchestrator.DetachedContinuationSource)
                    )
                    sigil.publish(trigger).map { _ =>
                      scribe.info(s"Detached task ${invokeId.value} (`${invokedName.value}`) completed — continuation trigger published")
                    }.handleError(t => Task {
                      scribe.error(s"Detached tool ${invokedName.value}: continuation publish failed", t)
                    })
                  }
                publishLate.flatMap(_ => publishSettle).flatMap(_ => continuation)
              }
            }.guarantee(Task(sigil.completeDetachedTool(invokeId)))
              .handleError(t => Task {
                scribe.error(s"Detached tool ${invokedName.value} (${invokeId.value}): completion watcher failed", t)
              })
              .startUnit()
            Task.pure(stamp(invokeId, invokedName)(drainedNow.map(e => e: Signal)) :+ (detachDelta: Signal))
        }
      } yield Stream.emits(signals)
    )
  }

  /** Poll `completed` every 25ms up to `thresholdMs`. Deliberately NOT
    * `Task.race` — racing would cancel the losing side, and the tool's
    * resolution fiber must keep running when the threshold wins. */
  private def awaitWithinThreshold[A](completed: java.util.concurrent.atomic.AtomicReference[Option[A]],
                                      thresholdMs: Long): Task[Option[A]] = {
    val deadline = System.currentTimeMillis() + math.max(0L, thresholdMs)
    def loop: Task[Option[A]] = Task.defer {
      completed.get() match {
        case some @ Some(_) => Task.pure(some)
        case None if System.currentTimeMillis() >= deadline => Task.pure(None)
        case None => Task.sleep(scala.concurrent.duration.FiniteDuration(25, "ms")).flatMap(_ => loop)
      }
    }
    loop
  }

  // ---- Resolution unwrap ---------------------------------------------

  /** Run the tool's [[Resolution]] against a defensively-cast input,
    * mapping any throwable (including a `ClassCastException` from a
    * mismatched input) to a recoverable [[ToolResult.Failure]]. Total —
    * never errors. */
  private def resolveResult(tool: Tool)(input: ToolInput, context: ToolContext): Task[ToolResult[tool.Output]] =
    Task(input.asInstanceOf[tool.Input])
      .flatMap { typed =>
        tool.resolution match {
          case Resolution.Simple(run)   => run(typed, context).map(ToolResult.success)
          case Resolution.Explicit(run) => run(typed, context)
        }
      }
      .handleError { err =>
        Task.pure(ToolResult.failure(
          message = Option(err.getMessage).getOrElse(err.getClass.getSimpleName),
          args    = renderInputArgs(tool, input)
        ))
      }

  // ---- Settle: bound output → paired result delta --------------------

  /** Build the settling [[ToolDelta]] (and the typed envelope) from a
    * resolution — folds output, outcome, and `state = Complete` onto
    * the originating `ToolInvoke` in one update. Bounding never
    * replaces the typed output: on overflow the full rendered form is
    * file-backed (or truncated inline when no workspace is bound), the
    * bounded head rides `summary`, and `output` stays the real value
    * with an [[OverflowPointer]] marking the bounding. */
  private def settle(tool: Tool)(result: ToolResult[tool.Output],
                                 context: ToolContext): Task[(ToolDelta, Option[ToolResultEnvelope[tool.Output]])] = {
    val invokeId = context.invokeId
    result match {
      case ToolResult.Success(value) =>
        // Measure + externalize the UNWRAPPED text for a TextToolOutput:
        // the inner text IS the result, so the overflow file holds clean
        // content a later grep/read_file consumes. A structured output
        // that opts into a clean-text render (`modelText`) measures +
        // overflows on THAT text; others externalize their compact JSON.
        val rendered = value match {
          case t: TextToolOutput => t.text
          case o                 => o.modelText.getOrElse(JsonFormatter.Compact(tool.outputRW.read(o)))
        }
        val threshold = context.sigil.inlineContentThreshold
        val resolved: Task[(Option[String], Option[OverflowPointer])] =
          if (tool.boundsOutputItself || !context.overflowLargeResults || rendered.length.toLong <= threshold)
            Task.pure((None, None))
          else buildOverflow(tool)(value, rendered, threshold, context)
        resolved.map { case (summaryOpt, pointer) =>
          val delta = ToolDelta(
            target         = invokeId,
            conversationId = context.conversation.id,
            state          = Some(EventState.Complete),
            summary        = summaryOpt,
            output         = Some(value),
            outcome        = Some(ToolOutcome.Success),
            overflow       = pointer
          )
          (delta, Some(ToolResultEnvelope(value, pointer)))
        }
      case ToolResult.Failure(message, hint, args) =>
        val body = (List(message) ++ hint.toList.map(h => s"\n\nHint: $h") ++
          args.toList.map(a => s"\n\nFailing args: $a")).mkString
        Task.pure((ToolDelta(
          target         = invokeId,
          conversationId = context.conversation.id,
          state          = Some(EventState.Complete),
          summary        = Some(body),
          // No real `output` — outcome carries the failure. The
          // invoke's `output` field stays `ToolOutput.Pending`.
          outcome        = Some(ToolOutcome.Failure(body, recoverable = true))
        ), None))
    }
  }

  /** A success result that overflows the inline threshold is written to
    * a file under the conversation's `FileSystemContext`
    * (`.sigil/output/<convId>/<tool>-<callId>.txt`); the returned
    * summary is a bounded head + the path + stats, so the agent
    * recovers the rest with the filesystem tools it already has
    * (`grep` / `read_file`). Because the write goes through the same
    * context those tools use, the file lands where they run (local or
    * ProxyTool-remote). Falls back to inline truncate-and-tell when no
    * workspace is bound or the write fails. */
  private def buildOverflow(tool: Tool)(value: tool.Output,
                                        rendered: String,
                                        threshold: Long,
                                        context: ToolContext): Task[(Option[String], Option[OverflowPointer])] = {
    val head  = tool.summarize(value, rendered)
    val lines = rendered.count(_ == '\n') + 1
    val truncateAndTell =
      head + "\n\n" +
        s"[${tool.name.value}: result is ${rendered.length} bytes / $lines lines (over the $threshold-byte inline limit), " +
        "truncated. Narrow your inputs to see the rest.]"
    val inlineFallback: (Option[String], Option[OverflowPointer]) =
      (Some(truncateAndTell), Some(OverflowPointer(path = None, bytes = rendered.length.toLong, lines = lines)))
    context.sigil.fileSystemContextFor(context.conversation.id).flatMap {
      case Some(fs) =>
        val relPath = s".sigil/output/${context.conversation.id.value}/${tool.name.value}-${context.invokeId.value}.txt"
        // Show the ABSOLUTE path when the context can name one — a
        // relative pointer forces the model to guess the resolution
        // root, and `.sigil` is a hidden directory the default
        // glob/grep excludes. The write itself stays context-relative.
        val shownPath = fs.absolutePathFor(relPath).getOrElse(relPath)
        fs.writeFile(relPath, rendered).map { bytes =>
          val summary = head + "\n\n" +
            s"[${tool.name.value}: full result is $lines lines / $bytes bytes — written to $shownPath. " +
            "Use grep or read_file on that path to see the rest.]"
          (Some(summary), Some(OverflowPointer(path = Some(shownPath), bytes = bytes, lines = lines)))
        }.handleError(_ => Task.pure(inlineFallback))
      case None => Task.pure(inlineFallback)
    }
  }

  /** Render the failing input to compact JSON for a
    * [[ToolResult.Failure]]'s `args`. Best-effort — never a hard
    * failure of the error path. */
  private def renderInputArgs(tool: Tool, input: ToolInput): Option[String] =
    try Some(JsonFormatter.Compact(tool.inputRW.read(input.asInstanceOf[tool.Input])))
    catch { case _: Throwable => None }

  // ---- Stamping ------------------------------------------------------

  /** Origin-stamp ancillary Events (paired with a settling
    * `StateDelta`) and mirror the respond family's `internal` flag on
    * any [[ToolDelta]] so the result-settle delta matches the
    * input-settle delta the orchestrator already emitted. */
  private def stamp(invokeId: Id[Event], invokedName: ToolName)(signals: List[Signal]): List[Signal] = {
    val toolIsInternal = RespondFamilyTool.contains(invokedName)
    signals.flatMap {
      case ev: Event =>
        val stamped = if (ev.origin.isDefined) ev else ev.withOrigin(Some(invokeId))
        List[Signal](
          stamped,
          StateDelta(target = stamped._id, conversationId = stamped.conversationId, state = EventState.Complete)
        )
      case td: ToolDelta if toolIsInternal && !td.internal => List(td.copy(internal = true))
      case other => List(other)
    }
  }

  // ---- Gates ---------------------------------------------------------

  /** Verify a [[sigil.event.ToolApproval]] exists before dispatching a
    * consent-gated tool. Returns `Right(())` to proceed; `Left(signals)`
    * to short-circuit dispatch with a Tool-role refusal Message the
    * agent reads on its next iteration. */
  private def consentOutcome(tool: Tool,
                             context: TurnContext,
                             originatingInvokeId: Id[Event],
                             consentGated: Boolean): Task[Either[List[Signal], Unit]] =
    if (!consentGated) Task.pure(Right(()))
    else if (_root_.sigil.orchestrator.Orchestrator.isAutonomousPosture(context)) Task.pure(Right(()))
    else context.sigil.latestToolApproval(tool.name, context.conversation._id).map {
      case Some(approval) if approval.approved => Right(())
      case Some(declined) =>
        val reason = declined.reason.map(r => s" — $r").getOrElse("")
        val body =
          s"""Tool `${tool.name.value}` cannot run — user previously declined this action$reason.
             |
             |If the user's intent has changed, ask them again (e.g. via `respond_options`) and
             |record the new decision with `record_consent("${tool.name.value}", approved=true,
             |reason="...")` before retrying.""".stripMargin
        Left(refusalSignals(body, context, originatingInvokeId))
      case None =>
        val question = tool.consentPrompt.map(p => s"""Suggested question: "$p"\n\n""").getOrElse("")
        val body =
          s"""Tool `${tool.name.value}` requires user consent before running.
             |
             |${question}Ask the user (typically via `respond_options` listing this action), wait for the
             |reply, then call `record_consent("${tool.name.value}", approved=true, reason="...")`
             |and retry the tool. The framework refuses to dispatch consent-gated tools without
             |a `ToolApproval` record in this conversation.""".stripMargin
        Left(refusalSignals(body, context, originatingInvokeId))
    }

  /** Run every [[Tool.preconditions]] check. If any returns
    * [[ToolPreconditionResult.Unsatisfied]], yield a Role.Tool Message
    * describing the blocked state instead of letting the resolution
    * run. The Message is paired to the originating ToolInvoke so
    * FrameBuilder threads it under that call. */
  private def preflightOutcome(tool: Tool,
                               context: TurnContext,
                               originatingInvokeId: Id[Event]): Task[Either[List[Signal], Unit]] =
    checkPreconditions(tool, context).map { unsatisfied =>
      if (unsatisfied.isEmpty) Right(())
      else {
        val lines = unsatisfied.map { case (n, reason, fix) =>
          val fixHint = fix.map(f => s" — try `$f`").getOrElse("")
          s"- **$n**: $reason$fixHint"
        }.mkString("\n")
        val body =
          s"""Tool `${tool.name.value}` cannot run yet — preconditions not met:
             |
             |$lines
             |
             |Resolve the blocked items, then retry.""".stripMargin
        Left(refusalSignals(body, context, originatingInvokeId))
      }
    }

  private def checkPreconditions(tool: Tool,
                                 context: TurnContext): Task[List[(String, String, Option[String])]] =
    if (tool.preconditions.isEmpty) Task.pure(Nil)
    else Task.sequence(tool.preconditions.map(p => p.check(context).map(p.name -> _))).map { results =>
      results.collect {
        case (n, ToolPreconditionResult.Unsatisfied(reason, fix)) => (n, reason, fix)
      }
    }

  private def refusalSignals(body: String,
                             context: TurnContext,
                             originatingInvokeId: Id[Event]): List[Signal] = {
    val msg = Message(
      participantId  = context.caller,
      conversationId = context.conversation.id,
      topicId        = context.conversation.currentTopicId,
      content        = Vector(ResponseContent.Text(body)),
      disposition    = MessageDisposition.Failure(recoverable = true),
      role           = MessageRole.Tool,
      state          = EventState.Complete,
      origin         = Some(originatingInvokeId)
    )
    List[Signal](
      msg,
      StateDelta(target = msg._id, conversationId = msg.conversationId, state = EventState.Complete)
    )
  }
}
