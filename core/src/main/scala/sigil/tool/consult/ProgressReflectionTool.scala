package sigil.tool.consult

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.provider.{ClassificationWork, GenerationSettings, ReasoningMode, WorkType}
import sigil.tool.{TextToolOutput, Tool, ToolName, ToolResult}

/**
 * Internal-only tool the framework forces the agent to call as
 * part of a periodic progress checkpoint. The tool's typed input
 * IS the checkpoint payload — the framework reads it directly
 * (no `executeResult` body), persists a [[sigil.event.ProgressCheckpoint]]
 * Event, and decides whether to continue the agent loop or to
 * intervene.
 */
case object ProgressReflectionTool extends Tool with FrameworkConsult {
  type Input  = ProgressReflectionInput
  type Output = TextToolOutput
  val inputRW: RW[ProgressReflectionInput] = summon[RW[ProgressReflectionInput]]
  val outputRW: RW[TextToolOutput]         = summon[RW[TextToolOutput]]

  val name: ToolName = ToolName("report_progress")
  val description: String =
    """Report your progress checkpoint relative to the prior status anchor in the system prompt.
      |
      |Pick a `currentStatus` (one line summary of where things stand RIGHT NOW), set
      |`meaningfulProgress = true` only when you're in a substantively different place than the
      |prior status (NOT just because you ran a tool). `remainingSteps` is one line on what's
      |left. `stuckOn` is empty string (or the field unset) when making progress; otherwise one
      |line on what's blocking. Set `shouldAskUser = true` only if you genuinely need the user
      |to clarify something to proceed.
      |
      |Be honest — if your status looks identical to the prior status or you're cycling through
      |the same searches, say so (`meaningfulProgress = false`) so the framework can intervene.""".stripMargin


  /** Quick self-assessment — routes through the cheap classification tier. */
  override def consultWorkType: WorkType = ClassificationWork

  /** Output is five short fields. 256 tokens covers the structured
    * payload plus the reasoning-spill margin. */
  override def consultSettings: GenerationSettings = GenerationSettings(
    maxOutputTokens = Some(256),
    reasoningMode = ReasoningMode.Off
  )

  /** Never executed — the framework reads the typed input directly via
    * [[ConsultTool.invoke]]. Resolves to an empty success for completeness. */
  override def executeResult(input: ProgressReflectionInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.success(TextToolOutput("")))
}
