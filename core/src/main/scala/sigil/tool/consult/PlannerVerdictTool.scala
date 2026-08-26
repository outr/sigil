package sigil.tool.consult

import fabric.rw.*
import rapid.Task
import sigil.tool.ToolContext
import sigil.provider.{AnalysisWork, GenerationSettings, OutputTokenCap, ReasoningMode, WorkType}
import sigil.tool.{DiscoverySpec, Effect, Freshness, Resolution, TextToolOutput, Tool, ToolIO, ToolName, ToolProfile, ToolResult, ToolSpec}

/**
 * Internal-only tool the framework forces the planner model to call
 * at a planner-tier progress checkpoint. The tool's typed input IS
 * the verdict payload — the framework reads it directly (no
 * `executeResult` body), routes the verdict (on_track / deviating /
 * replan), and maintains the turn's [[sigil.conversation.TurnPlan]]
 * from the returned plan fields.
 */
case object PlannerVerdictTool extends Tool with FrameworkConsult {
  type Input  = PlannerVerdictInput
  type Output = TextToolOutput
  val io: ToolIO[PlannerVerdictInput, TextToolOutput] = ToolIO.derived[PlannerVerdictInput, TextToolOutput]

  override val name: ToolName = ToolName("planner_verdict")
  override val description: String =
    """Deliver your planning-tier verdict on the executor's trajectory against the plan.
      |
      |Set `verdict` to "on_track" when the window's work is converging on the plan's done
      |criteria, "deviating" when the executor has lost the plot (undoing its own work,
      |grinding outside the objective, repeating work that cannot converge), or "replan"
      |when the plan itself no longer fits the task.
      |
      |`correction` is a concrete directive to the executor — required for "deviating",
      |omitted otherwise. `currentPhase` is always required: one short line on where the
      |work stands now.
      |
      |The plan fields (`objective`, `constraints`, `doneCriteria`) are returned ONLY on
      |your first review (no plan exists yet) and on "replan" — keep them concise: the
      |objective in one to three sentences, constraints as short phrases. On every other
      |verdict omit them entirely; the plan you were shown is retained for you.""".stripMargin

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Stable)),
    discovery = DiscoverySpec(kind = ConsultKind)
  )

  /** Strategic oversight — the model is fixed by `Sigil.plannerModelId`,
    * so this WorkType is declarative only (no routing happens). */
  override def consultWorkType: WorkType = AnalysisWork

  /** Output is a verdict plus, on first review / replan only, the
    * plan fields. The routine reply (verdict + correction + phase) is
    * small; 1024 tokens leaves headroom for the plan-carrying replies
    * so a detailed objective doesn't truncate the tool-call JSON
    * mid-object. */
  override def consultSettings: GenerationSettings = GenerationSettings(
    outputTokenCap = OutputTokenCap.Below(1024),
    reasoningMode  = ReasoningMode.Off
  )

  /** Never executed — the framework reads the typed input directly via
    * [[ConsultTool.invoke]]. Resolves to an empty success for completeness. */
  protected def resolve: Resolution[Input, Output] = Resolution.Explicit(executeResult)

  private def executeResult(input: PlannerVerdictInput, context: ToolContext): Task[ToolResult[TextToolOutput]] =
    Task.pure(ToolResult.success(TextToolOutput("")))
}
