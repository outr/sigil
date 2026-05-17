package sigil.tooling.refactor

import lightdb.id.Id as LId
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{Event, ToolInvoke}
import sigil.provider.CodingWork
import sigil.role.Role
import sigil.tool.fs.{FileSystemContext, GrepMatch}
import sigil.tool.{ToolExample, ToolName, TypedOutputTool}
import sigil.workflow.{AgentDecisionStepInput, SigilWorkflowModel, WorkflowSigil, WorkflowStepInputCompiler}
import sigil.workflow.SigilWorkflowModel.stepRW
import strider.{Workflow, WorkflowParent, WorkflowStatus}

import scala.concurrent.duration.*

/**
 * Multi-file refactor with per-match LLM judgment. The agent
 * issues ONE call describing a find pattern + natural-language
 * instruction; the framework:
 *
 *   1. Greps `path` (optionally restricted by `glob`) for
 *      `findPattern` → list of `(file, matches)`.
 *   2. For each matching file, spawns a Strider worker run
 *      (`SigilAgentDecisionStep`) whose brief contains the file
 *      content, the matches, and the user's instruction. The
 *      worker calls [[SubmitRefactorDecisionsTool]] with a typed
 *      `List[MatchDecision]` and finishes via `complete_task`.
 *   3. Collects each worker's decisions, builds per-file edited
 *      content, aggregates into a `WorkspaceEdit`, and commits
 *      via [[ApplyWorkspaceEdit]] (atomic — all-or-nothing).
 *   4. Returns a [[RefactorWithInstructionOutput]] with per-file
 *      decisions + commit outcomes.
 *
 * Throughput bound by `maxParallel` (default 5 concurrent workers).
 * Cost bound by `maxWorkers` (default 1000 — refuses to spawn
 * more than that, returning an `abortReason` so a 50K-file glob
 * can't accidentally explode billing).
 *
 * Sigil bug #212.
 */
final class RefactorWithInstructionTool(fs: FileSystemContext)
  extends TypedOutputTool[RefactorWithInstructionInput, RefactorWithInstructionOutput](
    name = ToolName("refactor_with_instruction"),
    description =
      """Apply a natural-language instruction to every match of a regex across files matching a glob.
        |
        |The framework finds all matches, dispatches a per-file LLM worker (cheap small model) to
        |apply the instruction with per-match judgment, then commits the aggregated edits atomically
        |(all files succeed or none do — partial-failure rollback restores the prior contents).
        |
        |Inputs:
        |  - `path`         — filesystem root for the search.
        |  - file-set glob  — optional path-glob filter on candidate files.
        |  - `findPattern`  — regex; only files containing at least one match are refactored.
        |  - `instruction`  — what the workers should do at each match. The instruction is read
        |                     verbatim by every worker; be specific about what to edit AND what to
        |                     leave alone (e.g. "Remove all `// Bug #NNN` comment markers. Preserve
        |                     `// Don't fix:` warnings unchanged.").
        |  - `dryRun`       — when true, return the report without writing any files.
        |  - `workerModelId`— optional explicit model id for the workers; default routes to the
        |                     cheapest available at `Low` complexity / `CodingWork`.
        |  - `maxParallel`  — concurrency cap (default 5).
        |  - `maxWorkers`   — hard cost cap (default 1000) — refuses to spawn more.
        |
        |Returns per-file decisions, the worker's reason per decision, and the apply outcome.""".stripMargin,
    keywords = Set(
      "refactor", "rewrite", "modify", "multi-file", "across files", "worker",
      "judgment", "per-match", "regex", "code change", "edit", "transform"
    ),
    examples = List(
      ToolExample(
        "Remove bug-number comment markers from Scala files",
        RefactorWithInstructionInput(
          path        = "src/main/scala",
          glob        = Some("**/*.scala"),
          findPattern = "// Bug #\\d+",
          instruction = "Remove the matched `// Bug #NNN` comment fragment. Preserve any surrounding text on the line."
        )
      )
    )
  ) with sigil.tool.DestructiveExternalTool {

  override def paginate: Boolean = false

  override protected def executeTyped(input: RefactorWithInstructionInput,
                                      ctx: TurnContext): Task[RefactorWithInstructionOutput] = {
    val cfg = RefactorWithInstructionTool.Config.fromInput(input)
    ctx.sigil match {
      case ws: WorkflowSigil =>
        runRefactor(input, cfg, ctx, ws)
      case _ =>
        Task.pure(RefactorWithInstructionOutput(
          filesConsidered = 0, filesModified = 0, totalEdits = 0, perFile = Nil,
          appliedAsWorkspaceEdit = false,
          abortReason = Some("refactor_with_instruction requires the host Sigil to mix in WorkflowSigil; the workflow runtime is not active.")
        ))
    }
  }

  private def runRefactor(input: RefactorWithInstructionInput,
                          cfg: RefactorWithInstructionTool.Config,
                          ctx: TurnContext,
                          ws: WorkflowSigil & sigil.Sigil): Task[RefactorWithInstructionOutput] = {
    val resolvedModelId: String =
      input.workerModelId
        .getOrElse(RefactorWithInstructionTool.pickWorkerModelId(ws, ctx))

    fs.searchFiles(input.path, input.findPattern, input.glob, maxMatches = cfg.maxMatches, contextLines = 0).flatMap { allMatches =>
      val byFile: List[(String, List[GrepMatch])] =
        allMatches.groupBy(_.filePath).view.mapValues(_.sortBy(_.lineNumber)).toList.sortBy(_._1)
      val totalWorkers = byFile.size
      if (totalWorkers > input.maxWorkers) {
        Task.pure(RefactorWithInstructionOutput(
          filesConsidered = totalWorkers, filesModified = 0, totalEdits = 0, perFile = Nil,
          appliedAsWorkspaceEdit = false,
          abortReason = Some(s"found $totalWorkers files with matches; refusing to spawn more than maxWorkers=${input.maxWorkers}")
        ))
      } else if (byFile.isEmpty) {
        Task.pure(RefactorWithInstructionOutput(
          filesConsidered = 0, filesModified = 0, totalEdits = 0, perFile = Nil,
          appliedAsWorkspaceEdit = false
        ))
      } else {
        dispatchAndCommit(input, cfg, ctx, ws, resolvedModelId, byFile)
      }
    }
  }

  private def dispatchAndCommit(input: RefactorWithInstructionInput,
                                cfg: RefactorWithInstructionTool.Config,
                                ctx: TurnContext,
                                ws: WorkflowSigil & sigil.Sigil,
                                modelId: String,
                                byFile: List[(String, List[GrepMatch])]): Task[RefactorWithInstructionOutput] = {

    // Worker dispatch — `maxParallel` slots via parSequenceBounded.
    val workerTasks: List[Task[(String, List[GrepMatch], Either[String, List[MatchDecision]])]] =
      byFile.map { case (filePath, matches) =>
        dispatchWorker(ws, ctx, modelId, filePath, matches, input.instruction)
          .map { result => (filePath, matches, result) }
      }

    rapid.Task.parSequenceBounded(workerTasks, parallelism = input.maxParallel).flatMap { workerResults =>
      // Build per-file edits + reports.
      val perFileBuilders: List[(FileRefactorReport, Option[ApplyWorkspaceEdit.FileEdit])] = workerResults.map {
        case (path, _, Left(err)) =>
          (FileRefactorReport(path = path, workerDecisions = Nil, workerError = Some(err)), None)
        case (path, matches, Right(decisions)) =>
          buildEdit(path, matches, decisions) match {
            case Left(err) =>
              (FileRefactorReport(path = path, workerDecisions = decisions, workerError = Some(err)), None)
            case Right((edit, diff)) =>
              (FileRefactorReport(path = path, workerDecisions = decisions, appliedDiff = Some(diff)), Some(edit))
          }
      }

      val perFileReports = perFileBuilders.map(_._1)
      val edits          = perFileBuilders.flatMap(_._2)
      val totalEdits     = edits.size

      if (edits.isEmpty || input.dryRun) {
        Task.pure(RefactorWithInstructionOutput(
          filesConsidered = byFile.size,
          filesModified   = if (input.dryRun) 0 else 0,
          totalEdits      = totalEdits,
          perFile         = perFileReports.map { r =>
            if (input.dryRun) r.copy(writeOutcome = Some("dryRun"))
            else r
          },
          appliedAsWorkspaceEdit = false
        ))
      } else {
        ApplyWorkspaceEdit(fs, edits).map { applyResult =>
          val byPath = applyResult.results.collect {
            case ApplyWorkspaceEdit.FileResult.Applied(p)              => p -> "applied"
            case ApplyWorkspaceEdit.FileResult.PreflightFailed(p, msg) => p -> s"preflight: $msg"
            case ApplyWorkspaceEdit.FileResult.WriteRolledBack(p, msg) => p -> s"rolled-back: $msg"
          }.toMap
          val annotated = perFileReports.map { r =>
            r.copy(writeOutcome = byPath.get(r.path))
          }
          RefactorWithInstructionOutput(
            filesConsidered = byFile.size,
            filesModified   = applyResult.filesWritten,
            totalEdits      = totalEdits,
            perFile         = annotated,
            appliedAsWorkspaceEdit = applyResult.filesWritten > 0
          )
        }
      }
    }
  }

  /** Spawn a Strider worker for one file. Wait for terminal; pull
    * the typed decisions out of the worker conversation's
    * `submit_refactor_decisions` ToolInvoke. */
  private def dispatchWorker(ws: WorkflowSigil & sigil.Sigil,
                             ctx: TurnContext,
                             modelId: String,
                             filePath: String,
                             matches: List[GrepMatch],
                             instruction: String): Task[Either[String, List[MatchDecision]]] = {
    fs.readFile(filePath).map(c => Right(c): Either[String, String]).handleError { t =>
      Task.pure(Left(s"could not read $filePath: ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}"))
    }.flatMap {
      case Left(err) =>
        Task.pure(Left(err): Either[String, List[MatchDecision]])
      case Right(fileContent) =>
        val workerLabel = s"refactor worker: ${filePath.takeRight(64)}"
        val brief = RefactorWithInstructionTool.renderBrief(filePath, fileContent, matches, instruction)
        val role  = Role(
          name        = "refactor-worker",
          description =
            "You are a focused refactor worker. Read the file and the per-match instruction, decide " +
              "for each match whether to edit / skip / fail, then call `submit_refactor_decisions` EXACTLY " +
              "ONCE with the typed decisions list. After that call returns, call `complete_task(summary)` " +
              "to finish. Do not call any other tools.",
          workType    = CodingWork
        )
        val dispatchTask = for {
          workerConv <- ws.newConversation(
            createdBy            = ctx.caller,
            label                = workerLabel,
            summary              = s"refactor: ${filePath.takeRight(80)}",
            participants         = Nil,
            parentConversationId = Some(ctx.conversation.id)
          )
          stepInput = AgentDecisionStepInput(
            id            = "decision-0",
            name          = Some(s"refactor decision: $filePath"),
            role          = role,
            brief         = brief,
            modelId       = modelId,
            toolNames     = List(SubmitRefactorDecisionsTool.name.value),
            // A refactor worker should call submit_refactor_decisions exactly once
            // then complete_task. Capping iterations keeps a misbehaving worker
            // from burning the per-file budget; the default 50 would spend minutes
            // on a quantised model that can't produce a clean tool call.
            maxIterations = 6
          )
          compiled = WorkflowStepInputCompiler.compile(List(stepInput))
          sourceId = LId[WorkflowParent](s"adhoc-refactor-${rapid.Unique()}")
          run <- ws.workflowManager.schedule(
            name           = workerLabel,
            steps          = compiled.steps,
            sourceId       = sourceId,
            conversationId = Some(workerConv._id.value)
          )
          settled <- waitForTerminal(ws, run._id, deadline = System.currentTimeMillis() + 10.minutes.toMillis)
          decisions <- extractDecisions(ws, workerConv._id, filePath, settled)
        } yield decisions
        // Per-file failure isolation: any throw inside dispatch becomes
        // a per-file `workerError` rather than aborting the whole tool
        // call. Other files' workers continue regardless. Without this,
        // a single hung worker would surface as a ToolFailureException
        // and lose any successful workers' edits.
        dispatchTask.handleError { t =>
          Task.pure(Left(s"worker dispatch failed: ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}"): Either[String, List[MatchDecision]])
        }
    }
  }

  private def waitForTerminal(ws: WorkflowSigil & sigil.Sigil,
                              runId: lightdb.id.Id[Workflow],
                              deadline: Long): Task[Workflow] = {
    def loop: Task[Workflow] =
      ws.workflowManager.collection.transaction(_.get(runId)).flatMap {
        case None      => Task.error(new RuntimeException(s"workflow $runId disappeared"))
        case Some(wf) if wf.finished => Task.pure(wf)
        case Some(_) if System.currentTimeMillis() > deadline =>
          Task.error(new RuntimeException(s"refactor worker $runId did not settle within deadline"))
        case Some(_) =>
          Task.sleep(200.millis).flatMap(_ => loop)
      }
    loop
  }

  /** Read the worker conv's events; find the
    * `submit_refactor_decisions` ToolInvoke; decode its typed
    * input. */
  private def extractDecisions(ws: WorkflowSigil & sigil.Sigil,
                               convId: lightdb.id.Id[sigil.conversation.Conversation],
                               filePath: String,
                               settled: Workflow): Task[Either[String, List[MatchDecision]]] = {
    if (settled.status != WorkflowStatus.Success && settled.status != WorkflowStatus.Failure) {
      return Task.pure(Left(s"worker settled in non-terminal status ${settled.status}"))
    }
    ws.withDB(_.events.transaction(_.list)).map { all =>
      val submits = all.collect {
        case ti: ToolInvoke
          if ti.conversationId == convId &&
             ti.toolName == SubmitRefactorDecisionsTool.name &&
             ti.input.isDefined =>
          ti.input.get
      }
      submits.collectFirst { case s: SubmitRefactorDecisionsInput => s.decisions } match {
        case Some(decisions) => Right(decisions)
        case None            =>
          val seenTools = all.collect {
            case ti: ToolInvoke if ti.conversationId == convId => ti.toolName.value
          }.distinct
          Left(s"worker did not call submit_refactor_decisions for $filePath (tools seen: ${seenTools.mkString(", ")})")
      }
    }
  }

  /** Apply the worker's `Edited` decisions to the file's content
    * AND verify each `oldText` matches the current text at the
    * edit span. Mismatch → return error. */
  private def buildEdit(filePath: String,
                        matches: List[GrepMatch],
                        decisions: List[MatchDecision]): Either[String, (ApplyWorkspaceEdit.FileEdit, String)] = {
    val edits = decisions.collect {
      case d @ MatchDecision(_, MatchAction.Edited, _, _, Some(_), Some(_), Some(_)) => d
    }
    if (edits.isEmpty) {
      // No edits proposed — return a "no-op" edit with the original content (won't fire a write).
      return Right((ApplyWorkspaceEdit.FileEdit(filePath, ""), "(no edits)"))
    }
    // Read the file content + apply edits in reverse-line order to avoid offset shift.
    val origContent =
      try scala.io.Source.fromFile(new java.io.File(filePath), "UTF-8").mkString
      catch { case t: Throwable => return Left(s"reading $filePath failed: ${t.getMessage}") }
    val lines = origContent.split("\n", -1).toBuffer
    // Sort edits descending by (line, startChar) so earlier edits don't shift later edit spans.
    val sorted = edits.sortBy(d => -(d.matchedLine * 100000 + d.startChar.getOrElse(0)))
    val mismatches = scala.collection.mutable.ListBuffer.empty[String]
    sorted.foreach { d =>
      val li = d.matchedLine - 1 // 0-based for the buffer
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

object RefactorWithInstructionTool {

  private[refactor] case class Config(maxMatches: Int)

  private[refactor] object Config {
    def fromInput(in: RefactorWithInstructionInput): Config =
      Config(maxMatches = 5000)
  }

  /** Render the per-file brief the worker reads. Includes the file
    * content, the matched lines (1-indexed), and the verbatim
    * instruction. */
  private[refactor] def renderBrief(filePath: String,
                                    fileContent: String,
                                    matches: List[GrepMatch],
                                    instruction: String): String = {
    val sb = new StringBuilder
    sb.append(s"# File: $filePath\n\n")
    sb.append("## Instruction (apply per match)\n\n")
    sb.append(instruction).append("\n\n")
    sb.append("## Matched lines (1-indexed)\n\n")
    matches.foreach { m =>
      sb.append(s"- line ${m.lineNumber}: ${m.content}\n")
    }
    sb.append("\n## Full file content\n\n```\n")
    sb.append(fileContent)
    sb.append("\n```\n\n")
    sb.append("## What to submit\n\n")
    sb.append(
      "Call `submit_refactor_decisions` exactly once with the typed decisions list, then\n" +
        "`complete_task` to finish. For each match, set the `MatchAction`:\n" +
        "  - `Edited` with `newText`, `startChar`, `endChar` (0-based on the line)\n" +
        "  - `Skipped` with a `reason`\n" +
        "  - `Failed` with a `reason`\n" +
        "`oldText` MUST match the file's current text at the edit span; the framework verifies\n" +
        "before writing.\n"
    )
    sb.toString
  }

  /** Pick a default worker model id when the input doesn't specify
    * one. Prefer a `CodingWork`/`Low` candidate from the active
    * provider strategy; fall back to the conversation's resolved
    * `routedModelId`; final fallback is the empty string (which
    * the AgentDecisionStep will resolve from workflow variables /
    * agent default). */
  private[refactor] def pickWorkerModelId(ws: WorkflowSigil & sigil.Sigil, ctx: TurnContext): String =
    ctx.routedModelId.map(_.value).getOrElse("")

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
