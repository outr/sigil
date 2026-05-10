package sigil.tool.provider

import fabric.rw.*

/**
 * Snapshot of every model-resolution layer that influences this
 * conversation's dispatch. Each layer is independent — `pinned`
 * outranks `assignedStrategy` outranks the framework default, and
 * `lastUsed` reports what actually ran on the most recent agent
 * Message regardless of those layers (a model swap mid-conversation
 * leaves a trail).
 *
 *   - `pinned` — set when [[PinModelTool]] is active for this
 *     conversation; `None` otherwise.
 *   - `assignedStrategy` — saved strategy assigned to the
 *     conversation's space (via [[SwitchModelTool]] or
 *     [[sigil.Sigil.assignProviderStrategy]]); `None` when no
 *     assignment exists.
 *   - `lastUsed` — the model that produced the most recent agent
 *     Message in this conversation; `None` for fresh conversations.
 *   - `resolved` — the model dispatch would pick on the next turn,
 *     after the pin / strategy / fallback chain. Lets the agent
 *     answer "which model are you about to call?" without simulating
 *     the strategy itself.
 */
case class CurrentModelOutput(pinned: Option[ModelReference],
                              assignedStrategy: Option[AssignedStrategySummary],
                              lastUsed: Option[ModelReference],
                              resolved: Option[ModelReference]) derives RW

/**
 * Pointer to a model id alongside the registry record (when one
 * exists). Unregistered ids (custom providers, phantom strings) come
 * back as `summary = None` — the caller can still see the literal id
 * and react ("I don't know what 'foo' is — try `list_models`").
 */
case class ModelReference(id: String, summary: Option[ModelSummary]) derives RW

/** Saved strategy slice — id, label, and the headline candidate. */
case class AssignedStrategySummary(id: String,
                                   label: String,
                                   primaryCandidate: Option[ModelReference]) derives RW
