package sigil.signal

import fabric.rw.*
import lightdb.id.Id
import sigil.conversation.Conversation

/**
 * Lifecycle pulse for a framework-internal long-running operation
 * (pre-flight, context compression, frame load, …). Bug #50.
 *
 * Distinct from the persistent `WorkflowRunStarted` /
 * `WorkflowStepCompleted` / `WorkflowRunCompleted` Events emitted
 * by application-level workflows (Strider runs). Those are
 * [[sigil.event.ControlPlaneEvent]]s — durable, replayable,
 * carry conversation / participant / topic context. This Notice
 * is transient — broadcast to live subscribers, no persistence,
 * no replay. The reason: a turn fires pre-flight every iteration;
 * persisting a started/completed pair per pre-flight pollutes
 * `db.events` with operational noise nothing reads later.
 *
 * Clients consuming `Sigil.signals` filter on this Notice to render
 * an activity bar / latency-trace / progress overlay. Threshold-
 * gating (don't paint sub-300ms workflows) lives client-side; the
 * framework emits unconditionally so the data is there when needed.
 *
 * `workflowType` is the broad category (`"preflight"`,
 * `"compress"`, `"frame-load"`, …). Apps that wrap their own
 * framework-internal operations contribute new types — the
 * framework treats the field as opaque.
 *
 * `conversationId` is `None` for cross-conversation operations
 * (rare); set for the typical conversation-scoped case so client
 * UIs can scope rendering to the active conversation.
 */
case class FrameworkWorkflowNotice(workflowId: String,
                                   workflowType: String,
                                   phase: FrameworkWorkflowPhase,
                                   conversationId: Option[Id[Conversation]] = None)
  extends Notice derives RW
