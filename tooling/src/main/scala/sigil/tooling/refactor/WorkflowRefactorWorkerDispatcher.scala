package sigil.tooling.refactor

import lightdb.id.Id as LId
import rapid.Task
import sigil.TurnContext
import sigil.event.ToolInvoke
import sigil.provider.CodingWork
import sigil.role.Role
import sigil.tool.fs.{FileSystemContext, GrepMatch}
import sigil.workflow.{AgentDecisionStepInput, WorkflowSigil, WorkflowStepInputCompiler}
import sigil.workflow.SigilWorkflowModel.stepRW
import strider.{Workflow, WorkflowParent, WorkflowStatus}

import scala.concurrent.duration.*

/**
 * Production worker dispatcher — spawns a Strider
 * `SigilAgentDecisionStep` run per file, drives it through a real
 * LLM, and reads the `submit_refactor_decisions` typed input back
 * out of the worker conversation's events. Used by
 * [[RefactorWithInstructionTool]]'s default constructor; tests
 * inject a deterministic [[RefactorWorkerDispatcher]] instead.
 */
final class WorkflowRefactorWorkerDispatcher(fs: FileSystemContext,
                                             ws: WorkflowSigil & sigil.Sigil)
  extends RefactorWorkerDispatcher {

  override def dispatch(ctx: TurnContext,
                        modelId: String,
                        filePath: String,
                        matches: List[GrepMatch],
                        instruction: String): Task[Either[String, List[MatchDecision]]] =
    fs.readFile(filePath).map(c => Right(c): Either[String, String]).handleError { t =>
      Task.pure(Left(s"could not read $filePath: ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}"))
    }.flatMap {
      case Left(err) =>
        Task.pure(Left(err): Either[String, List[MatchDecision]])
      case Right(fileContent) =>
        val workerLabel = s"refactor worker: ${filePath.takeRight(64)}"
        val brief = WorkflowRefactorWorkerDispatcher.renderBrief(filePath, fileContent, matches, instruction)
        val role = Role(
          name = "refactor-worker",
          description =
            "You are a focused refactor worker. Read the file and the per-match instruction, decide " +
              "for each match whether to edit / skip / fail, then call `submit_refactor_decisions` EXACTLY " +
              "ONCE with the typed decisions list. After that call returns, call `complete_task(summary)` " +
              "to finish. Do not call any other tools.",
          workType = CodingWork
        )
        val dispatchTask = for {
          workerConv <- ws.newConversation(
            createdBy = ctx.caller,
            label = workerLabel,
            summary = s"refactor: ${filePath.takeRight(80)}",
            participants = Nil,
            parentConversationId = Some(ctx.conversation.id)
          )
          stepInput = AgentDecisionStepInput(
            id = "decision-0",
            name = Some(s"refactor decision: $filePath"),
            role = role,
            brief = brief,
            modelId = modelId,
            toolNames = List(SubmitRefactorDecisionsTool.name.value),
            maxIterations = 6
          )
          compiled = WorkflowStepInputCompiler.compile(List(stepInput))
          sourceId = LId[WorkflowParent](s"adhoc-refactor-${rapid.Unique()}")
          run <- ws.workflowManager.schedule(
            name = workerLabel,
            steps = compiled.steps,
            sourceId = sourceId,
            conversationId = Some(workerConv._id.value)
          )
          settled <- waitForTerminal(ws, run._id, deadline = System.currentTimeMillis() + 10.minutes.toMillis)
          decisions <- extractDecisions(ws, workerConv._id, filePath, settled)
        } yield decisions
        dispatchTask.handleError { t =>
          Task.pure(Left(s"worker dispatch failed: ${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}"): Either[
            String,
            List[MatchDecision]])
        }
    }

  private def waitForTerminal(ws: WorkflowSigil & sigil.Sigil,
                              runId: lightdb.id.Id[Workflow],
                              deadline: Long): Task[Workflow] = {
    def loop: Task[Workflow] =
      ws.workflowManager.collection.transaction(_.get(runId)).flatMap {
        case None => Task.error(new RuntimeException(s"workflow $runId disappeared"))
        case Some(wf) if wf.finished => Task.pure(wf)
        case Some(_) if System.currentTimeMillis() > deadline =>
          Task.error(new RuntimeException(s"refactor worker $runId did not settle within deadline"))
        case Some(_) =>
          Task.sleep(200.millis).flatMap(_ => loop)
      }
    loop
  }

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
        case None =>
          val seenTools = all.collect {
            case ti: ToolInvoke if ti.conversationId == convId => ti.toolName.value
          }.distinct
          Left(s"worker did not call submit_refactor_decisions for $filePath (tools seen: ${seenTools.mkString(", ")})")
      }
    }
  }
}

object WorkflowRefactorWorkerDispatcher {

  /**
   * Render the per-file brief the worker reads. Includes the file
   * content, the matched lines (1-indexed), and the verbatim
   * instruction.
   */
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
}
