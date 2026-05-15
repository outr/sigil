package sigil.tool.provider

import fabric.rw.*
import lightdb.time.Timestamp
import rapid.{Stream, Task}
import sigil.TurnContext
import sigil.event.{ComplexityChange, Event, Message, MessageRole, MessageVisibility}
import sigil.provider.Complexity
import sigil.signal.EventState
import sigil.tool.{ToolExample, ToolInput, ToolName, TypedTool}
import sigil.tool.model.ResponseContent

case class PinComplexityInput(tier: String) extends ToolInput derives RW

/**
 * Pin this conversation's routing-complexity tier
 * (`Low` / `Medium` / `High` / `VeryHigh`). The classifier's
 * per-message `inferComplexity` round-trip is bypassed; every
 * turn resolves to whichever candidate in
 * [[sigil.provider.ProviderStrategy.routed]]'s chain supports the
 * pinned tier. Lets users override the classifier without naming
 * a specific model — the routing chain still picks the best
 * available model for that tier.
 *
 * Cleared via [[UnpinComplexityTool]]. Not auto-registered; apps
 * add this to `staticTools` when they want the surface exposed.
 * Bug #152.
 */
case object PinComplexityTool extends TypedTool[PinComplexityInput](
  name = ToolName("pin_complexity"),
  description =
    """Pin this conversation's routing complexity tier
      |(`low` / `medium` / `high` / `very-high`). Every turn routes
      |to whichever candidate in the strategy chain supports that
      |tier, regardless of the classifier's per-message verdict.
      |
      |Use when:
      |  - The classifier is repeatedly mis-routing (force the right tier)
      |  - You want a cost ceiling without naming a model (`pin_complexity("medium")`
      |    keeps spend bounded even if the frontier model is in the chain)
      |  - You're diagnosing routing behavior and need a stable tier
      |
      |Distinct from pinning a specific model: this pins a TIER and lets the chain
      |choose the best fit. If the pinned-tier model is unavailable for a turn,
      |routing degrades to the next candidate that still supports the tier — pinning
      |a specific model can't degrade.
      |
      |Use `unpin_complexity` to revert.""".stripMargin,
  examples = List(
    ToolExample("Pin to medium tier",     PinComplexityInput("medium")),
    ToolExample("Pin to frontier",        PinComplexityInput("very-high")),
    ToolExample("Local-only with low tier", PinComplexityInput("low"))
  ),
  keywords = Set(
    "pin", "lock", "force", "stick", "fix", "always", "deterministic",
    "complexity", "tier", "routing", "cost", "ceiling", "level"
  )
) {
  override protected def executeTyped(input: PinComplexityInput, ctx: TurnContext): Stream[Event] = {
    val normalized = input.tier.trim.toLowerCase.replaceAll("\\s+|-|_", "")
    val parsed: Option[Complexity] = normalized match {
      case "low"                            => Some(Complexity.Low)
      case "medium" | "med" | "mid"         => Some(Complexity.Medium)
      case "high"                           => Some(Complexity.High)
      case "veryhigh" | "vhigh" | "frontier" | "max" => Some(Complexity.VeryHigh)
      case _                                => None
    }
    parsed match {
      case None =>
        Stream.emit[Event](reply(ctx,
          s"Unrecognised tier '${input.tier}'. Valid tiers: `low`, `medium`, `high`, `very-high`. " +
            s"Use the closest match by capability — `medium` is the per-turn default for most chains."))
      case Some(tier) =>
        // Sigil bug #177 — capture the prior pinned tier so the emitted
        // ComplexityChange carries the correct previous→new transition
        // and `Reason.Pinned` vs `Reason.Repinned` is distinguishable
        // without consumers diffing `previousTier` / `newTier`.
        Stream.force(
          ctx.sigil.withDB(_.conversations.transaction { tx =>
            tx.get(ctx.conversation.id).flatMap {
              case None       => Task.pure(None)
              case Some(conv) =>
                val previous = conv.pinnedComplexity
                tx.upsert(conv.copy(pinnedComplexity = Some(tier), modified = Timestamp()))
                  .map(_ => Some(previous))
            }
          }).map {
            case None =>
              // Conversation row vanished between dispatch and execute —
              // surface a Tool-role failure rather than silently swallowing.
              Stream.emit[Event](reply(ctx,
                s"Could not pin complexity: conversation row not found. Try again from a live session."))
            case Some(previous) =>
              val reason =
                if (previous.isEmpty) ComplexityChange.Reason.Pinned
                else ComplexityChange.Reason.Repinned
              Stream.emits[Event](List(
                ComplexityChange(
                  participantId  = ctx.caller,
                  conversationId = ctx.conversation.id,
                  topicId        = ctx.conversation.currentTopicId,
                  previousTier   = previous,
                  newTier        = Some(tier),
                  reason         = reason
                ),
                reply(ctx,
                  s"Pinned to `$tier` complexity tier. Every LLM call in this conversation will route to that " +
                    s"tier's candidate until `unpin_complexity` is called.")
              ))
          }
        )
    }
  }

  private def reply(ctx: TurnContext, text: String): Message = Message(
    participantId  = ctx.caller,
    conversationId = ctx.conversation.id,
    topicId        = ctx.conversation.currentTopicId,
    content        = Vector(ResponseContent.Text(text)),
    state          = EventState.Complete,
    role           = MessageRole.Tool,
    // Sigil bug #164 — keep the confirmation Agents-only so the agent's
    // mandatory `respond` doesn't have to compete with a duplicate chat
    // bubble from the tool itself. The tool's text still feeds the
    // agent's next iteration; the user sees only the agent's `respond`.
    visibility     = MessageVisibility.Agents
  )
}
