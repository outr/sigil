package sigil.tooling.refactor

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.TurnContext
import sigil.event.Event
import sigil.tool.fs.{FileSystemContext, GrepMatch}
import sigil.tool.output.ToolOutputNode
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.workflow.WorkflowSigil

import scala.concurrent.duration.*

/**
 * Multi-file refactor with per-match LLM judgment — the prepare
 * step of the three-tool refactor session.
 *
 *   1. Greps `path` (optionally restricted by `glob`) for
 *      `findPattern` → list of `(file, matches)`.
 *   2. For each matching file, dispatches a per-file worker via
 *      the configured [[RefactorWorkerDispatcher]]. Default
 *      production dispatcher spawns a Strider worker run; tests
 *      inject a deterministic stub.
 *   3. Builds per-file edited content, parks the draft
 *      [[ApplyWorkspaceEdit.FileEdit]] set in
 *      [[RefactorSessionStore]] under a fresh `sessionId`, and
 *      drains every per-file [[FileRefactorReport]] into
 *      `db.toolOutputs` so the agent can navigate via
 *      `next_page` / `query_tool_output`.
 *   4. Returns a [[RefactorWithInstructionOutput]] carrying the
 *      `sessionId` + first-page slice + standard pagination
 *      fields. **Does not write to disk** — that's the apply
 *      step's job.
 *
 * Throughput bound by `maxParallel` (default 5 concurrent workers).
 * Cost bound by `maxWorkers` (default 1000 — refuses to spawn
 * more than that, returning an `abortReason` so a 50K-file glob
 * can't accidentally explode billing).
 */
final class RefactorWithInstructionTool(fs: FileSystemContext,
                                        sessionStore: RefactorSessionStore,
                                        workerDispatcher: Option[RefactorWorkerDispatcher] = None,
                                        firstPageSize: Int = 50,
                                        rowTtl: FiniteDuration = 30.minutes)
  extends TypedOutputTool[RefactorWithInstructionInput, RefactorWithInstructionOutput](
    name = ToolName("refactor_with_instruction"),
    description =
      """Prepare a multi-file refactor: find every regex match across files matching a glob and
        |dispatch a per-file LLM worker (cheap small model) to decide how the instruction applies
        |at each match. The framework aggregates the worker decisions into a draft workspace edit
        |stored under a fresh `sessionId` and returned alongside a paginated first page of diffs.
        |
        |This tool DOES NOT write to disk. Inspect the diffs, then commit the prepared session
        |with the refactor-apply tool — or drop it with the refactor-cancel tool. Subsequent
        |pages of diffs are reachable via the standard pagination tools using the returned
        |`sessionId` as the `referenceId`.
        |
        |Inputs:
        |  - `path`         — filesystem root for the search.
        |  - file-set glob  — optional path-glob filter on candidate files.
        |  - `findPattern`  — regex; only files containing at least one match are refactored.
        |  - `instruction`  — what the workers should do at each match. The instruction is read
        |                     verbatim by every worker; be specific about what to edit AND what to
        |                     leave alone (e.g. "Remove all `// Bug #NNN` comment markers. Preserve
        |                     `// Don't fix:` warnings unchanged.").
        |  - `workerModelId`— optional explicit model id for the workers; default routes to the
        |                     cheapest available at `Low` complexity for coding work.
        |  - `maxParallel`  — concurrency cap (default 5).
        |  - `maxWorkers`   — hard cost cap (default 1000) — refuses to spawn more.
        |
        |Returns the `sessionId`, first-page diffs, and pagination cursors. Sessions are kept in
        |memory for 30 minutes by default; an unconsumed session past that window is treated as
        |cancelled.""".stripMargin,
    keywords = Set(
      "refactor",
      "rewrite",
      "modify",
      "multi-file",
      "across files",
      "worker",
      "judgment",
      "per-match",
      "regex",
      "code change",
      "edit",
      "transform",
      "find",
      "replace",
      "find and replace",
      "search and replace",
      "search and edit",
      "find and edit",
      "bulk edit",
      "bulk replace",
      "rewrite across files",
      "remove",
      "delete pattern",
      "substitute",
      "search",
      "match"
    ),
    examples = List(
      ToolExample(
        "Prepare a refactor that removes bug-number comment markers from Scala files",
        RefactorWithInstructionInput(
          path = "src/main/scala",
          glob = Some("**/*.scala"),
          findPattern = "// Bug #\\d+",
          instruction = "Remove the matched `// Bug #NNN` comment fragment. Preserve any surrounding text on the line."
        )
      )
    )
  )
  with sigil.tool.DestructiveExternalTool {

  override def paginate: Boolean = false

  override protected def executeTyped(input: RefactorWithInstructionInput,
                                      ctx: TurnContext): Task[RefactorWithInstructionOutput] = {
    val dispatcherOpt: Option[RefactorWorkerDispatcher] =
      workerDispatcher.orElse(ctx.sigil match {
        case ws: WorkflowSigil => Some(new WorkflowRefactorWorkerDispatcher(fs, ws))
        case _ => None
      })
    dispatcherOpt match {
      case None =>
        Task.pure(emptyOutputWithAbort(
          ctx,
          "refactor_with_instruction requires either an injected RefactorWorkerDispatcher or a host Sigil mixing in WorkflowSigil; neither was available."
        ))
      case Some(dispatcher) =>
        runRefactor(input, ctx, dispatcher)
    }
  }

  private def emptyOutputWithAbort(ctx: TurnContext, reason: String): RefactorWithInstructionOutput = {
    val callId = ctx.currentToolInvokeId.getOrElse(Event.id())
    RefactorWithInstructionOutput(
      sessionId = callId.value,
      totalDiffs = 0,
      filesAffected = 0,
      page0Diffs = Nil,
      hasMore = false,
      nodeIds = Nil,
      callId = callId,
      referenceId = callId.value,
      pageSize = firstPageSize,
      perFileSummary = Map.empty,
      abortReason = Some(reason)
    )
  }

  private def runRefactor(input: RefactorWithInstructionInput,
                          ctx: TurnContext,
                          dispatcher: RefactorWorkerDispatcher): Task[RefactorWithInstructionOutput] = {
    val resolvedModelId: String =
      input.workerModelId.getOrElse(ctx.routedModelId.map(_.value).getOrElse(""))

    fs.searchFiles(input.path, input.findPattern, input.glob, maxMatches = 5000, contextLines = 0).flatMap { allMatches =>
      val byFile: List[(String, List[GrepMatch])] =
        allMatches.groupBy(_.filePath).view.mapValues(_.sortBy(_.lineNumber)).toList.sortBy(_._1)
      val totalWorkers = byFile.size
      if (totalWorkers > input.maxWorkers) {
        Task.pure(emptyOutputWithAbort(
          ctx,
          s"found $totalWorkers files with matches; refusing to spawn more than maxWorkers=${input.maxWorkers}"
        ))
      } else if (byFile.isEmpty) {
        // No candidates — still register an empty session so the
        // caller's wire shape stays consistent and so a cancel /
        // apply against the returned id finds something to act on.
        val callId = ctx.currentToolInvokeId.getOrElse(Event.id())
        sessionStore.create(RefactorSession(
          sessionId = callId.value,
          edits = Nil,
          perFile = Nil,
          createdAtMillis = System.currentTimeMillis()
        ))
        Task.pure(RefactorWithInstructionOutput(
          sessionId = callId.value,
          totalDiffs = 0,
          filesAffected = 0,
          page0Diffs = Nil,
          hasMore = false,
          nodeIds = Nil,
          callId = callId,
          referenceId = callId.value,
          pageSize = firstPageSize,
          perFileSummary = Map.empty
        ))
      } else {
        dispatchAndStage(input, ctx, dispatcher, resolvedModelId, byFile)
      }
    }
  }

  private def dispatchAndStage(input: RefactorWithInstructionInput,
                               ctx: TurnContext,
                               dispatcher: RefactorWorkerDispatcher,
                               modelId: String,
                               byFile: List[(String, List[GrepMatch])]): Task[RefactorWithInstructionOutput] = {

    val workerTasks: List[Task[(String, List[GrepMatch], Either[String, List[MatchDecision]])]] =
      byFile.map { case (filePath, matches) =>
        dispatcher.dispatch(ctx, modelId, filePath, matches, input.instruction)
          .map(result => (filePath, matches, result))
      }

    Task.parSequenceBounded(workerTasks, parallelism = input.maxParallel).flatMap { workerResults =>
      val builderTasks: List[Task[(FileRefactorReport, Option[ApplyWorkspaceEdit.FileEdit])]] = workerResults.map {
        case (path, _, Left(err)) =>
          Task.pure((FileRefactorReport(path = path, workerDecisions = Nil, workerError = Some(err)), None))
        case (path, matches, Right(decisions)) =>
          buildEdit(path, matches, decisions).map {
            case Left(err) =>
              (FileRefactorReport(path = path, workerDecisions = decisions, workerError = Some(err)), None)
            case Right((edit, diff)) =>
              (FileRefactorReport(path = path, workerDecisions = decisions, appliedDiff = Some(diff)), Some(edit))
          }
      }
      Task.sequence(builderTasks).flatMap { perFileBuilders =>
        val perFileReports = perFileBuilders.map(_._1)
        val edits = perFileBuilders.flatMap(_._2)
        // Only count files that produced a real change; "no-op" edits
        // (an empty newContent placeholder when buildEdit had no
        // `Edited` decisions) still surface in the report but don't
        // count as affected.
        val filesAffected = edits.count(_.newContent.nonEmpty)

        val callId = ctx.currentToolInvokeId.getOrElse(Event.id())
        val sessId = callId.value

        sessionStore.create(RefactorSession(
          sessionId = sessId,
          edits = edits,
          perFile = perFileReports,
          createdAtMillis = System.currentTimeMillis()
        ))

        drainDiffsToToolOutputs(ctx, callId, perFileReports).map { drainedNodeIds =>
          val page0 = perFileReports.take(firstPageSize)
          RefactorWithInstructionOutput(
            sessionId = sessId,
            totalDiffs = perFileReports.size,
            filesAffected = filesAffected,
            page0Diffs = page0,
            hasMore = perFileReports.size > firstPageSize,
            nodeIds = drainedNodeIds.take(firstPageSize),
            callId = callId,
            referenceId = sessId,
            pageSize = firstPageSize,
            perFileSummary = perFileReports.map(r => r.path -> r.workerDecisions.size).toMap
          )
        }
      }
    }
  }

  /**
   * Drain one [[ToolOutputNode]] per per-file report into
   * `db.toolOutputs` so `next_page(referenceId = sessionId)`
   * walks subsequent pages of diffs. The session id IS the
   * `callId` and IS the top-level `referenceId`.
   */
  private def drainDiffsToToolOutputs(ctx: TurnContext,
                                      callId: Id[Event],
                                      reports: List[FileRefactorReport]): Task[List[String]] = {
    val convId = ctx.conversation.id
    val now = System.currentTimeMillis()
    val expiresAt = Timestamp(now + rowTtl.toMillis)

    def drainOne(report: FileRefactorReport, ordinal: Int): Task[String] = {
      val row = ToolOutputNode(
        conversationId = convId,
        callId = callId,
        referenceId = callId.value,
        level = 0,
        ordinal = ordinal,
        hasChildren = false,
        payload = summon[RW[FileRefactorReport]].read(report),
        expiresAt = expiresAt
      )
      ctx.sigil.withDB(_.toolOutputs.transaction(_.upsert(row))).map(_ => row._id.value)
    }

    Task.sequence(reports.zipWithIndex.map { case (r, i) => drainOne(r, i) })
  }

  /**
   * Apply the worker's `Edited` decisions to the file's content
   * AND verify each `oldText` matches the current text at the
   * edit span. Mismatch → return error. Returns `(edit, diff)`.
   * Reads via the configured [[FileSystemContext]] so sandbox /
   * relative-path resolution stays consistent with the rest of
   * the pipeline.
   */
  private def buildEdit(filePath: String,
                        matches: List[GrepMatch],
                        decisions: List[MatchDecision]): Task[Either[String, (ApplyWorkspaceEdit.FileEdit, String)]] = {
    val edits = decisions.collect {
      case d @ MatchDecision(_, MatchAction.Edited, _, _, Some(_), Some(_), Some(_)) => d
    }
    if (edits.isEmpty) {
      return Task.pure(Right((ApplyWorkspaceEdit.FileEdit(filePath, ""), "(no edits)")))
    }
    fs.readFile(filePath)
      .map(c => Right(c): Either[String, String])
      .handleError(t => Task.pure(Left(s"reading $filePath failed: ${t.getMessage}")))
      .map {
        case Left(err) => Left(err)
        case Right(origContent) =>
          val lines = origContent.split("\n", -1).toBuffer
          val sorted = edits.sortBy(d => -(d.matchedLine * 100000 + d.startChar.getOrElse(0)))
          val mismatches = scala.collection.mutable.ListBuffer.empty[String]
          sorted.foreach { d =>
            val li = d.matchedLine - 1
            if (li < 0 || li >= lines.size) {
              mismatches += s"line ${d.matchedLine} out of range"
            } else {
              val line = lines(li)
              val start = d.startChar.get
              val end = d.endChar.get
              if (start < 0 || end > line.length || start > end) {
                mismatches += s"line ${d.matchedLine}: range [$start, $end) out of bounds"
              } else {
                val actual = line.substring(start, end)
                if (actual != d.oldText) {
                  mismatches +=
                    s"line ${d.matchedLine}: oldText mismatch (worker said '${d.oldText.take(40)}'; file has '${actual.take(40)}')"
                } else {
                  lines(li) = line.substring(0, start) + d.newText.getOrElse("") + line.substring(end)
                }
              }
            }
          }
          if (mismatches.nonEmpty)
            Left(s"file $filePath: ${mismatches.size} edit(s) had bad oldText / range — aborted to avoid corrupting the file: ${mismatches.mkString("; ")}")
          else {
            val newContent = lines.mkString("\n")
            val diff = RefactorWithInstructionTool.unifiedDiff(filePath, origContent, newContent)
            Right((ApplyWorkspaceEdit.FileEdit(filePath, newContent), diff))
          }
      }
  }
}

object RefactorWithInstructionTool {

  /**
   * Plain unified-diff renderer (no LCS — just per-line emit if
   * lines differ). Sufficient for the report's `appliedDiff` field
   * which is forensic / human-readable.
   */
  private[refactor] def unifiedDiff(filePath: String, before: String, after: String): String = {
    if (before == after) return "(no change)"
    val sb = new StringBuilder
    sb.append(s"--- $filePath\n+++ $filePath\n")
    val a = before.split("\n", -1)
    val b = after.split("\n", -1)
    val n = math.max(a.length, b.length)
    var i = 0
    while (i < n) {
      val av = if (i < a.length) a(i) else null
      val bv = if (i < b.length) b(i) else null
      if (av == bv) {
        if (av != null) sb.append(" ").append(av).append("\n")
      } else {
        if (av != null) sb.append("-").append(av).append("\n")
        if (bv != null) sb.append("+").append(bv).append("\n")
      }
      i += 1
    }
    sb.toString
  }
}
