package sigil.workflow

import sigil.PolyType

/**
 * Open [[sigil.PolyType]] for app-defined workflow triggers — the
 * external events that pause a workflow and resume it on firing.
 * Mirrors the extensibility pattern of `Mode` / `Tool` / `WorkType`:
 * apps register concrete subtypes via
 * [[sigil.Sigil.workflowTriggerRegistrations]], and fabric's
 * polymorphic RW dispatches by simple class name on the wire.
 *
 * The framework ships four baseline triggers in
 * [[sigil.workflow.trigger]]:
 *
 *   - [[sigil.workflow.trigger.ConversationMessageTrigger]] — fires on
 *     a new Message in a target conversation matching a filter
 *   - [[sigil.workflow.trigger.TimeTrigger]] — cron / interval
 *   - [[sigil.workflow.trigger.WebhookTrigger]] — HTTP listener with
 *     a shared secret
 *   - [[sigil.workflow.trigger.WorkflowEventTrigger]] — cross-workflow
 *     named-event signaling
 *
 * App-specific triggers (Sage's Slack / Email / Git triggers) live
 * downstream and register on the consumer's Sigil subclass.
 *
 * Each trigger is *typed* — the wire shape carries the trigger's
 * concrete fields, not opaque JSON. The Dart codegen emits a real
 * class per registered subtype.
 *
 * `WorkflowTrigger.compile(sigil)` produces the underlying
 * `strider.step.Trigger` — the engine's primitive. The Sigil-side
 * type is what agents and persistence work with.
 */
trait WorkflowTrigger {

  /** Stable kind identifier — short string the LLM uses when
    * referring to this trigger family in tool inputs (`"conversation"`,
    * `"time"`, `"webhook"`, …). Distinct from `productPrefix` to keep
    * the agent-facing vocabulary stable across refactors. */
  def kind: String

  /** Compile to a Strider trigger primitive. Implementations create
    * the `strider.step.Trigger` that owns the actual register / check
    * / unregister logic. The framework calls this when scheduling a
    * workflow that includes this trigger. */
  def compile(host: _root_.sigil.Sigil): strider.step.Trigger
}

object WorkflowTrigger extends PolyType[WorkflowTrigger]
