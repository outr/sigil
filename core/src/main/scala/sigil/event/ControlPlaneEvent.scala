package sigil.event

/**
 * Marker trait for [[Event]] subtypes that are control-plane only —
 * lifecycle / status / wire-coordination signals that should NOT
 * produce a [[sigil.conversation.ContextFrame]]. The framework's
 * `FrameBuilder.appendFor` skips control-plane events automatically;
 * they still flow through `Sigil.publish` (persisted, broadcast,
 * fan-out via TriggerFilter) so wire consumers see them, but the
 * conversation projection stays clean.
 *
 * Examples (framework-shipped): the workflow lifecycle Events
 * (`WorkflowRunStarted` / `WorkflowStepCompleted` /
 * `WorkflowRunCompleted` / `WorkflowRunFailed`) — they're status
 * updates the UI renders specially, not entries in the agent's
 * conversational context.
 *
 * Apps that want a custom Event to live alongside Messages in the
 * conversation view leave this trait off and add a case to
 * `FrameBuilder.appendFor`.
 */
trait ControlPlaneEvent extends Event
