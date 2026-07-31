package sigil.tool

/**
 * Pre-execution gates the orchestrator enforces before a tool runs:
 *
 *   - `consent` — when set, the framework refuses to dispatch until a
 *     [[sigil.event.ToolApproval]] record exists for the conversation;
 *     the [[ConsentSpec]] carries the question the agent asks.
 *   - `preconditions` — descriptive checks run before execution; an
 *     unsatisfied one skips the call and surfaces a recoverable
 *     failure naming the gap.
 *   - `requiresAccessibleSpaces` — the tool is filtered out of rosters
 *     and discovery for chains with no accessible memory space.
 */
final case class ToolGates(consent: Option[ConsentSpec] = None,
                           preconditions: List[ToolPrecondition] = Nil,
                           requiresAccessibleSpaces: Boolean = false)

object ToolGates {
  val none: ToolGates = ToolGates()
}
