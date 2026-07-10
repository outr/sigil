package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.Task
import sigil.provider.Effort
import sigil.tool.ToolContext
import sigil.tool.{TextToolOutput, Tool, ToolExample, ToolInput, ToolName, ToolResult}

case class PinEffortInput(level: String) extends ToolInput derives RW

/**
 * Pin this conversation's reasoning effort (`low` / `medium` / `high` /
 * `max`). The main agent turn's resolved [[sigil.provider.GenerationSettings]]
 * is overlaid with the pinned effort and reasoning is forced on, so a
 * consumer can expose a per-conversation effort picker without touching
 * the deployment-global [[sigil.provider.ProviderStrategy]] candidate
 * settings. Governs the user-facing agent turn only — framework auxiliary
 * calls (classifier, memory extractor, summarization) keep their own
 * settings.
 *
 * The level is taken as a string (not the [[Effort]] enum) so the arg is
 * a flat, model-fillable scalar — [[Effort.Custom]] is a nested union
 * variant and would make the input unfillable. Cleared via
 * [[UnpinEffortTool]]. Not auto-registered; apps add this to
 * `staticTools` when they want the surface exposed.
 */
case object PinEffortTool extends Tool {
  type Input  = PinEffortInput
  type Output = TextToolOutput
  val inputRW  = summon[RW[PinEffortInput]]
  val outputRW = summon[RW[TextToolOutput]]
  val name = ToolName("pin_effort")
  val description =
    """Pin this conversation's reasoning effort (`low` / `medium` / `high` / `max`). Every agent
      |turn applies that effort and forces reasoning on, regardless of the deployment's default.
      |Stays in effect until `unpin_effort` clears it.
      |
      |Use when the user wants to trade latency/cost for depth ("think harder on my replies",
      |"keep it quick and cheap"). Framework auxiliary calls (topic classifier, memory extractor,
      |summarization) stay on their own settings — the pin is about the user's replies.""".stripMargin
  override val examples = List(
    ToolExample("Think harder", PinEffortInput("high")),
    ToolExample("Maximum deliberation", PinEffortInput("max")),
    ToolExample("Keep it quick", PinEffortInput("low"))
  )
  override val keywords = Set(
    "pin", "lock", "force", "always", "effort", "reasoning", "thinking",
    "think", "depth", "deliberation", "budget", "harder", "quality"
  )

  override def executeResult(input: PinEffortInput,
                             ctx: ToolContext): Task[ToolResult[TextToolOutput]] = {
    val normalized = input.level.trim.toLowerCase.replaceAll("\\s+|-|_", "")
    val parsed: Option[Effort] = normalized match {
      case "low" | "fast" | "quick"            => Some(Effort.Low)
      case "medium" | "med" | "mid" | "default" => Some(Effort.Medium)
      case "high"                              => Some(Effort.High)
      case "max" | "maximum" | "highest" | "full" => Some(Effort.Max)
      case _                                   => None
    }
    parsed match {
      case None =>
        Task.pure(ToolResult.failure(
          s"Unrecognised effort level '${input.level}'. Valid levels: `low`, `medium`, `high`, `max`.",
          hint = Some("Pick by how much deliberation the turn warrants — `medium` is a balanced default.")
        ))
      case Some(effort) =>
        ctx.sigil.withDB(_.conversations.transaction(_.modify(ctx.conversation.id) {
          case None       => Task.pure(None)
          case Some(conv) => Task.pure(Some(conv.copy(pinnedEffort = Some(effort), modified = Timestamp())))
        })).map {
          case None =>
            ToolResult.failure(
              "Could not pin effort: conversation row not found. Try again from a live session.")
          case Some(_) =>
            ToolResult.Success(TextToolOutput(
              s"Pinned reasoning effort to `${effort}`. The agent's turns in this conversation will " +
                "apply that effort until `unpin_effort` is called."))
        }
    }
  }
}
