package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.provider.TokenUsage

/**
 * A transient update to an active [[sigil.event.ToolInvoke]]. Carries the
 * parsed `input` at completion time (when the LLM has finished streaming args
 * and a concrete `ToolInput` is available) and/or a state transition.
 *
 * `error` carries a human-readable diagnostic when the call settled because of
 * a failure rather than a successful arg-parse — typically a post-decode
 * validator rejection (`@pattern` / length / type mismatch) or a provider-side
 * error mid-call. Client UIs that render `inputJson` should prefer `error`
 * when it's present so failed calls show "(invalid args: …)" rather than
 * the "(input pending)" placeholder reserved for genuinely-mid-flight calls.
 * Bug #51 — without this, validator-rejected chips were
 * indistinguishable from in-flight calls.
 */
case class ToolDelta(target: Id[Event],
                     conversationId: Id[Conversation],
                     input: Option[sigil.tool.ToolInput] = None,
                     state: Option[EventState] = None,
                     error: Option[String] = None,
                     /** Mirror of [[sigil.event.ToolInvoke.internal]] — set
                       * by the orchestrator to match the target invoke's
                       * own flag, so client UIs that filter the chip
                       * lifecycle have a stable signal across both events.
                       * Bug #56. */
                     internal: Boolean = false,
                     /** Trailing-usage attribution. The provider's
                       * [[sigil.provider.ProviderEvent.Usage]] event lands on
                       * the invoke when no user-visible Message exists to
                       * carry it (tool-call-only turns: change_mode,
                       * cancel, find_capability, …). Cost projection picks
                       * it up off the persisted `ToolInvoke.usage` field
                       * combined with `ToolInvoke.modelId`. */
                     usage: Option[TokenUsage] = None,
                     /** Live tool-author-driven summary update. Set via
                       * [[sigil.TurnContext.setSummary]] from inside a
                       * tool's execute path; folded onto
                       * [[sigil.event.ToolInvoke.summary]]. Sigil bug
                       * #191 — wire-event channel that drives the inline
                       * tool-chip tagline UI consumers render. */
                     summary: Option[String] = None)
  extends Delta derives RW {

  /**
   * Apply this delta to a [[sigil.event.ToolInvoke]]. Sets `input` (the parsed args),
   * `state`, and `usage` from any present fields. Returns `target` unchanged if it
   * isn't a `ToolInvoke`.
   */
  override def apply(target: Event): Event = target match {
    case t: sigil.event.ToolInvoke =>
      val nextInput   = input.orElse(t.input)
      val nextState   = state.getOrElse(t.state)
      val nextUsage   = usage.getOrElse(t.usage)
      val nextSummary = summary.getOrElse(t.summary)
      t.copy(input = nextInput, state = nextState, usage = nextUsage, summary = nextSummary)
    case other => other
  }
}
