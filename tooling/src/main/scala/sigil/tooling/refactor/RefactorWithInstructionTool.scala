package sigil.tooling.refactor

import fabric.rw.*
import lightdb.id.Id
import lightdb.time.Timestamp
import rapid.Task
import sigil.TurnContext
import sigil.event.Event
import sigil.provider.{CodingWork, Complexity, ProviderStrategy}
import sigil.tool.fs.{FileSystemContext, GrepMatch}
import sigil.tool.output.ToolOutputNode
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.workflow.WorkflowSigil

import scala.concurrent.duration.*

/**
 * Multi-file refactor with per-match LLM judgment — the prepare
 * step of the three-tool refactor session, gated behind a two-
 * phase confirm so the caller sees the cost before paying it.
 *
 * Flow:
 *
 *   1. `confirmed = false` (default): greps `path` (optionally
 *      filtered by `glob`) for `findPattern`, counts matches per
 *      file, resolves the worker model id from the input's
 *      `complexity` against the chain's
 *      [[sigil.provider.ProviderStrategy]] for [[CodingWork]],
 *      and returns a [[RefactorWithInstructionOutput.Scope]]
 *      preview. NO workers run. The agent reviews the resolved
 *      file count + model id, then re-invokes with `confirmed =
 *      true` (and the same other parameters) to dispatch.
 *   2. `confirmed = true`: re-greps the same input, dispatches
 *      one per-file worker via the configured
 *      [[RefactorWorkerDispatcher]] (default production
 *      dispatcher spawns a Strider worker run; tests inject a
 *      deterministic stub), builds per-file edited content,
 *      parks the draft [[ApplyWorkspaceEdit.FileEdit]] set in
 *      [[RefactorSessionStore]] under a fresh `sessionId`, and
 *      drains every per-file [[FileRefactorReport]] into
 *      `db.toolOutputs` so the agent can navigate via
 *      `next_page` / `query_tool_output`. Returns a
 *      [[RefactorWithInstructionOutput.Dispatched]] carrying the
 *      `sessionId` + first-page slice + standard pagination
 *      fields. **Does not write to disk** — that's the apply
 *      step's job.
 *
 * Throughput bound by `maxParallel` (default 5 concurrent workers).
 * Cost bound by `maxFiles` (default 10000 — refuses to spawn
 * more than that, returning an `abortReason` so a 50K-file glob
 * can't accidentally explode billing).
 */
final class RefactorWithInstructionTool(fs: FileSystemContext,
                                        sessionStore: RefactorSessionStore,
                                        workerDispatcher: Option[RefactorWorkerDispatcher] = None,
                                        firstPageSize: Int = 50,
                                        rowTtl: FiniteDuration = 30.minutes,
                                        scopePreviewLimit: Int = 200)
  extends TypedOutputTool[RefactorWithInstructionInput, RefactorWithInstructionOutput](
    name = ToolName("refactor_with_instruction"),
    description =
      """Find-and-edit across many files in one call, gated behind a two-phase confirm so you
        |see the cost before paying it. First call with `confirmed = false` (the default): the
        |tool greps under `path` (optionally filtered by a path glob) for `findPattern`, counts
        |matches per file, resolves the worker model from the input's `complexity`, and returns
        |a scope preview — no workers dispatched, no disk writes. Review the preview, then re-call
        |with `confirmed = true` (and the same other parameters) to dispatch a small-model worker
        |per file to apply `instruction` at each match. The confirm call returns a `sessionId` plus
        |the proposed per-file diffs — nothing written to disk yet. Review the diffs, then commit
        |with `refactor_apply` or drop with `refactor_cancel`. Subsequent pages of diffs are
        |reachable via the standard pagination tools using the returned `sessionId` as the
        |`referenceId`.
        |
        |This is the first move for any multi-file pattern change — not the last. DO NOT grep
        |first to scout the matches; the tool's own grep IS the discovery, and the scope preview
        |IS the cost-aware version of that discovery. A separate pre-grep adds nothing the
        |preview doesn't already show and costs an extra round-trip.
        |
        |Use for: "remove all // Bug NNN comments", "rename foo to bar across every .scala",
        |"replace deprecated API X with Y at every callsite".
        |Don't use for: single-file edits (use the single-file edit tool), or read-only
        |inspection where you don't intend to act (use the grep tool — every file in the scope
        |preview becomes a paid LLM worker call on confirm).
        |
        |Inputs:
        |  - `path`         — filesystem root for the search.
        |  - file-set glob  — optional path-glob filter on candidate files.
        |  - `findPattern`  — regex; every file with at least one match gets its own paid LLM
        |                     worker call on confirm. Make this AS NARROW AS POSSIBLE — if you
        |                     can encode the filter in the regex (e.g. anchor to comment syntax
        |                     like `^[ \\t]*(//|/\\*|\\*).*pattern`), do it there, not in the
        |                     `instruction`. The worker is paid compute, not free filtering.
        |                     A pattern that matches 1500 files costs 1500 worker calls.
        |  - `instruction`  — what every worker should do at each match. Write it as a self-
        |                     contained directive — every worker sees ONLY this string plus
        |                     its file's matches. Be explicit about preserve-vs-edit if the
        |                     pattern could overmatch (e.g. "Remove // Bug NNN markers. Preserve
        |                     // Don't fix: warnings unchanged.").
        |  - `complexity`   — routing tier for the worker model. Use `Low` for mechanical
        |                     find/replace and `Medium`+ for edits that need to reason about
        |                     context. Required — no default.
        |  - `workerModelId`— optional explicit model id; when set, wins over routing. When
        |                     unset, the framework routes through ProviderStrategy at the
        |                     input's complexity for CodingWork.
        |  - `maxParallel`  — concurrency cap (default 5).
        |  - `maxFiles`     — hard cost cap (default 10000) — refuses to dispatch more.
        |  - `confirmed`    — false (default) returns the scope preview; true dispatches.
        |
        |Sessions are kept in memory for 30 minutes by default; an unconsumed session past
        |that window is treated as cancelled.""".stripMargin,
    keywords = Set(
      "refactor", "rewrite", "modify", "multi-file", "across files", "worker",
      "judgment", "per-match", "regex", "code change", "edit", "transform",
      "find", "replace", "find and replace", "search and replace",
      "search and edit", "find and edit", "bulk edit", "bulk replace",
      "rewrite across files", "remove", "delete pattern", "substitute",
      "search", "match"
    ),
    examples = List(
      ToolExample(
        "Preview a refactor that removes bug-number comment markers from Scala files (confirmed=false)",
        RefactorWithInstructionInput(
          path        = "src/main/scala",
          glob        = Some("**/*.scala"),
          findPattern = "// Bug #\\d+",
          instruction = "Remove the matched `// Bug #NNN` comment fragment. Preserve any surrounding text on the line.",
          complexity  = Complexity.Low
        )
      ),
      ToolExample(
        "Confirm the same refactor and stage the per-file diffs (confirmed=true)",
        RefactorWithInstructionInput(
          path        = "src/main/scala",
          glob        = Some("**/*.scala"),
          findPattern = "// Bug #\\d+",
          instruction = "Remove the matched `// Bug #NNN` comment fragment. Preserve any surrounding text on the line.",
          complexity  = Complexity.Low,
          confirmed   = true
        )
      )
    )
  ) with sigil.tool.DestructiveExternalTool {

  override def paginate: Boolean = false

  override protected def executeTyped(input: RefactorWithInstructionInput,
                                      ctx: TurnContext): Task[RefactorWithInstructionOutput] = {
    if (!input.confirmed) {
      runScopePreview(input, ctx)
    } else {
      runConfirmedDispatch(input, ctx)
    }
  }

  // ---- scope preview (confirmed = false) ----

  private def runScopePreview(input: RefactorWithInstructionInput,
                              ctx: TurnContext): Task[RefactorWithInstructionOutput] = {
    fs.searchFiles(input.path, input.findPattern, input.glob, maxMatches = 5000, contextLines = 0).flatMap { allMatches =>
      val byFile: List[(String, Int)] =
        allMatches.groupBy(_.filePath).view.mapValues(_.size).toList.sortBy { case (path, count) => (-count, path) }
      val totalFiles   = byFile.size
      val totalMatches = byFile.iterator.map(_._2).sum
      val capExceeded  = totalFiles > input.maxFiles

      resolveWorkerModelId(input, ctx).map { resolvedModelId =>
        val truncated   = byFile.size > scopePreviewLimit
        val previewMap  = byFile.take(scopePreviewLimit).toMap
        val sessionId   = ctx.currentToolInvokeId.map(_.value).getOrElse(Event.id().value)
        val abortReason: Option[String] =
          if (capExceeded)
            Some(s"found $totalFiles files with matches; refusing to dispatch more than maxFiles=${input.maxFiles}. " +
              s"Narrow `findPattern` or `glob` to bring the scope under the cap, then re-preview.")
          else None
        RefactorWithInstructionOutput.Scope(
          sessionId                   = sessionId,
          totalFiles                  = totalFiles,
          totalMatches                = totalMatches,
          perFileMatchCounts          = previewMap,
          perFileMatchCountsTruncated = truncated,
          resolvedModelId             = resolvedModelId,
          estimatedWorkerCallCount    = totalFiles,
          estimatedCostNote           = costNote(input.complexity, totalFiles),
          confirmCall                 = renderConfirmCall(input),
          abortReason                 = abortReason
        )
      }
    }
  }

  /** Resolve the worker model id. Explicit `workerModelId` wins;
    * otherwise walk the chain's accessible spaces, find the first
    * resolved `ProviderStrategy`, and pick the first
    * `candidates(CodingWork)` entry whose `supportedComplexity`
    * contains the input's complexity. Returns the empty string when
    * no strategy / candidate resolves — the agent reads the empty
    * value as "framework didn't route, fall back to your own
    * choice". */
  private def resolveWorkerModelId(input: RefactorWithInstructionInput,
                                   ctx: TurnContext): Task[String] = input.workerModelId match {
    case Some(explicit) if explicit.nonEmpty => Task.pure(explicit)
    case _ =>
      def fromStrategy(strategy: ProviderStrategy): Option[String] =
        strategy.candidates(CodingWork)
          .find(_.supportedComplexity.contains(input.complexity))
          .map(_.modelId.value)

      ctx.sigil.accessibleSpaces(ctx.chain, ctx.conversation.id).flatMap { spaces =>
        val ordered = spaces.toList
        def loop(remaining: List[sigil.SpaceId]): Task[Option[String]] = remaining match {
          case Nil => Task.pure(None)
          case space :: rest =>
            ctx.sigil.resolveProviderStrategy(space).flatMap {
              case Some(strategy) => fromStrategy(strategy) match {
                case Some(id) => Task.pure(Some(id))
                case None     => loop(rest)
              }
              case None => loop(rest)
            }
        }
        loop(ordered).map(_.getOrElse(""))
      }
  }

  private def costNote(complexity: Complexity, files: Int): String = {
    val tier = complexity match {
      case Complexity.Low      => "cheapest tier"
      case Complexity.Medium   => "mid tier"
      case Complexity.High     => "high tier"
      case Complexity.VeryHigh => "frontier tier"
    }
    s"$files worker call(s) on $tier; one paid LLM call per file"
  }

  private def renderConfirmCall(input: RefactorWithInstructionInput): String = {
    val globPart = input.glob.map(g => s""", glob = "$g"""").getOrElse("")
    val modelPart = input.workerModelId.filter(_.nonEmpty).map(m => s""", workerModelId = "$m"""").getOrElse("")
    s"""refactor_with_instruction(path = "${input.path}"$globPart, findPattern = "${escape(input.findPattern)}", """ +
      s"""instruction = "...", complexity = Complexity.${input.complexity}$modelPart, """ +
      s"maxParallel = ${input.maxParallel}, maxFiles = ${input.maxFiles}, confirmed = true)"
  }

  private def escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

  // ---- confirmed dispatch (confirmed = true) ----

  private def runConfirmedDispatch(input: RefactorWithInstructionInput,
                                   ctx: TurnContext): Task[RefactorWithInstructionOutput] = {
    val dispatcherOpt: Option[RefactorWorkerDispatcher] =
      workerDispatcher.orElse(ctx.sigil match {
        case ws: WorkflowSigil => Some(new WorkflowRefactorWorkerDispatcher(fs, ws))
        case _                 => None
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

  private def emptyOutputWithAbort(ctx: TurnContext, reason: String): RefactorWithInstructionOutput.Dispatched = {
    val callId = ctx.currentToolInvokeId.getOrElse(Event.id())
    RefactorWithInstructionOutput.Dispatched(
      sessionId      = callId.value,
      totalDiffs     = 0,
      filesAffected  = 0,
      page0Diffs     = Nil,
      hasMore        = false,
      nodeIds        = Nil,
      callId         = callId,
      referenceId    = callId.value,
      pageSize       = firstPageSize,
      perFileSummary = Map.empty,
      abortReason    = Some(reason)
    )
  }

  private def runRefactor(input: RefactorWithInstructionInput,
                          ctx: TurnContext,
                          dispatcher: RefactorWorkerDispatcher): Task[RefactorWithInstructionOutput] = {
    resolveWorkerModelId(input, ctx).flatMap { resolvedModelId =>
      fs.searchFiles(input.path, input.findPattern, input.glob, maxMatches = 5000, contextLines = 0).flatMap { allMatches =>
        val byFile: List[(String, List[GrepMatch])] =
          allMatches.groupBy(_.filePath).view.mapValues(_.sortBy(_.lineNumber)).toList.sortBy(_._1)
        val totalFiles = byFile.size
        if (totalFiles > input.maxFiles) {
          Task.pure(emptyOutputWithAbort(
            ctx,
            s"found $totalFiles files with matches; refusing to dispatch more than maxFiles=${input.maxFiles}. " +
              s"Narrow `findPattern` or `glob` and re-preview."
          ))
        } else if (byFile.isEmpty) {
          // No candidates — still register an empty session so the
          // caller's wire shape stays consistent and so a cancel /
          // apply against the returned id finds something to act on.
          val callId = ctx.currentToolInvokeId.getOrElse(Event.id())
          sessionStore.create(RefactorSession(
            sessionId       = callId.value,
            edits           = Nil,
            perFile         = Nil,
            createdAtMillis = System.currentTimeMillis()
          ))
          Task.pure(RefactorWithInstructionOutput.Dispatched(
            sessionId      = callId.value,
            totalDiffs     = 0,
            filesAffected  = 0,
            page0Diffs     = Nil,
            hasMore        = false,
            nodeIds        = Nil,
            callId         = callId,
            referenceId    = callId.value,
            pageSize       = firstPageSize,
            perFileSummary = Map.empty
          ))
        } else {
          dispatchAndStage(input, ctx, dispatcher, resolvedModelId, byFile)
        }
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
          .map { result => (filePath, matches, result) }
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
        val edits          = perFileBuilders.flatMap(_._2)
        // Only count files that produced a real change; "no-op" edits
        // (an empty newContent placeholder when buildEdit had no
        // `Edited` decisions) still surface in the report but don't
        // count as affected.
        val filesAffected  = edits.count(_.newContent.nonEmpty)

        val callId  = ctx.currentToolInvokeId.getOrElse(Event.id())
        val sessId  = callId.value

        sessionStore.create(RefactorSession(
          sessionId       = sessId,
          edits           = edits,
          perFile         = perFileReports,
          createdAtMillis = System.currentTimeMillis()
        ))

        drainDiffsToToolOutputs(ctx, callId, perFileReports).map { drainedNodeIds =>
          val page0 = perFileReports.take(firstPageSize)
          RefactorWithInstructionOutput.Dispatched(
            sessionId      = sessId,
            totalDiffs     = perFileReports.size,
            filesAffected  = filesAffected,
            page0Diffs     = page0,
            hasMore        = perFileReports.size > firstPageSize,
            nodeIds        = drainedNodeIds.take(firstPageSize),
            callId         = callId,
            referenceId    = sessId,
            pageSize       = firstPageSize,
            perFileSummary = perFileReports.map(r => r.path -> r.workerDecisions.size).toMap
          )
        }
      }
    }
  }

  /** Drain one [[ToolOutputNode]] per per-file report into
    * `db.toolOutputs` so `next_page(referenceId = sessionId)`
    * walks subsequent pages of diffs. The session id IS the
    * `callId` and IS the top-level `referenceId`. */
  private def drainDiffsToToolOutputs(ctx: TurnContext,
                                      callId: Id[Event],
                                      reports: List[FileRefactorReport]): Task[List[String]] = {
    val convId    = ctx.conversation.id
    val now       = System.currentTimeMillis()
    val expiresAt = Timestamp(now + rowTtl.toMillis)

    def drainOne(report: FileRefactorReport, ordinal: Int): Task[String] = {
      val row = ToolOutputNode(
        conversationId = convId,
        callId         = callId,
        referenceId    = callId.value,
        level          = 0,
        ordinal        = ordinal,
        hasChildren    = false,
        payload        = summon[RW[FileRefactorReport]].read(report),
        expiresAt      = expiresAt
      )
      ctx.sigil.withDB(_.toolOutputs.transaction(_.upsert(row))).map(_ => row._id.value)
    }

    Task.sequence(reports.zipWithIndex.map { case (r, i) => drainOne(r, i) })
  }

  /** Apply the worker's `Edited` decisions to the file's content
    * AND verify each `oldText` matches the current text at the
    * edit span. Mismatch → return error. Returns `(edit, diff)`.
    * Reads via the configured [[FileSystemContext]] so sandbox /
    * relative-path resolution stays consistent with the rest of
    * the pipeline. */
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
              val line   = lines(li)
              val start  = d.startChar.get
              val end    = d.endChar.get
              if (start < 0 || end > line.length || start > end) {
                mismatches += s"line ${d.matchedLine}: range [$start, $end) out of bounds"
              } else {
                val actual = line.substring(start, end)
                if (actual != d.oldText) {
                  mismatches += s"line ${d.matchedLine}: oldText mismatch (worker said '${d.oldText.take(40)}'; file has '${actual.take(40)}')"
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

  /** Plain unified-diff renderer (no LCS — just per-line emit if
    * lines differ). Sufficient for the report's `appliedDiff` field
    * which is forensic / human-readable. */
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
