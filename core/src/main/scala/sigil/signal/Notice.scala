package sigil.signal

/**
 * Transient one-shot pulse — third Signal kind alongside [[sigil.event.Event]]
 * (persisted, lifecycle) and [[Delta]] (transient, mutates a target Event).
 *
 * Notices model the request/snapshot/lifecycle traffic that doesn't fit the
 * persisted-or-target-mutation shape:
 *
 *   - **Server-pushed broadcasts.** ConversationCreated / ConversationDeleted
 *     announce list-level changes; every interested viewer's stream sees them.
 *   - **Snapshots.** ConversationListSnapshot, ConversationSnapshot deliver a
 *     point-in-time payload to a single viewer (typically in response to a
 *     client request, but also valid as unsolicited server pushes).
 *   - **Client→server requests.** RequestConversationList,
 *     SwitchConversation, RequestSecret, RequestSecretVerify, RequestSecretSet
 *     are pulses the UI emits over the durable socket. The framework
 *     dispatches them through [[sigil.Sigil.handleNotice]].
 *
 * **Pairing intent without correlation.** There is no Request/Reply trait
 * split. A Notice is just a Notice; reply payloads carry enough domain
 * information (secretId, conversationId, etc.) to be matched against the
 * triggering request on the client side. This keeps the on-the-wire shape
 * minimal — no correlationId field — and lets the same response Notice be
 * sent unsolicited (e.g. server pushes a fresh snapshot when state changes).
 *
 * **Routing.** Notices flow over the same [[sigil.pipeline.SignalHub]] as
 * Events and Deltas. Two emission paths:
 *
 *   - `Sigil.publish(notice)` — broadcast to every subscriber's queue
 *   - `Sigil.publishTo(viewer, notice)` — single-target; only that viewer's
 *     subscriber queue receives it
 *
 * Notices are NOT persisted. They never enter `SigilDB.events`, never appear
 * in `SignalTransport.replay` results. Reconnecting clients that need state
 * issue a fresh request (`RequestConversationList`, `SwitchConversation`,
 * etc.) on connect.
 */
trait Notice extends Signal
