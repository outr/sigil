---
name: 66-readstatedelta-missing-participantid
description: ReadStateDelta carries target + conversationId + lastReadAt but no participantId, so consumers can't attribute the cursor advance to the right participant. The whole signal is unactionable without it.
type: project
---

# ❌ #66 — `ReadStateDelta` carries no `participantId`; consumers can't attribute the cursor advance

**Where:**
- Codegen: `sage/app/lib/ws/durable/model/sigil/signal/read_state_delta.dart`
  declares only `target` / `conversationId` / `lastReadAt`.
- Sigil source side: search `core/src/main/scala/sigil/signal/ReadStateDelta.scala`
  (or wherever the Scala definition lives — codegen names map directly).

**What's wrong:**

Sigil bug #62 shipped `ReadState` (event) and `ReadStateDelta`
(notice) so consumers could implement read-receipts. The pattern
mirrors how Sigil ships other event/delta pairs:

- `ReadState` — full snapshot. Carries `participantId` so the
  consumer knows whose cursor this is. ✓
- `ReadStateDelta` — incremental update. Carries
  `target` / `conversationId` / `lastReadAt`. **Does not carry
  `participantId`.** ✗

Without `participantId` on the delta, a consumer can't know which
participant's cursor advanced. The delta is effectively
unactionable — the consumer either:
- Ignores the delta entirely (tome's current behaviour, routed
  through `_logIgnored` with explanation in
  `sage/tome/lib/chat/sigil_chat_controller.dart` after Sigil bug
  #62 implementation landed in tome).
- Re-fetches the full `ReadState` on every delta to learn the
  participantId, defeating the purpose of having a delta.

The pattern Sigil uses for every other event/delta pair (e.g.,
`Message` / `MessageDelta`, `AgentState` / `AgentStateDelta`) is
"the delta carries enough context to route on its own without
re-resolving the parent." `ReadStateDelta` breaks that pattern.

**Reproduced (during tome migration):**

Tome's reactions+receipts implementation (per
`sage/tome/docs/reactions-and-receipts-spec.md`) added a
`case ReadStateDelta d:` arm in
`SigilChatController._handleSignal`. The intended behaviour was
`_lastReadOrdinal[d.participantId] = ...`. With no `participantId`
on the delta, the implementation has nothing to key on and routes
the delta to `_logIgnored` so the gap surfaces in debug logs:

> `[SAGE-IGNORED] ReadStateDelta — wire shape lacks participantId; can't route per-participant. See Sigil #66.`

Receipt-rendering in `MessageMetaLine` therefore only updates from
the (less frequent, full-snapshot) `ReadState` event — the optimistic
delta path is dead.

**Suggested fix:**

Add `participantId: ParticipantId` to `ReadStateDelta`. Same shape
the parent `ReadState` carries. Codegen regenerates with the new
field; consumers can route the cursor advance immediately.

Concrete shape (matches the existing field set):

```scala
final case class ReadStateDelta(
  target: Id[ReadState],
  conversationId: Id[Conversation],
  participantId: ParticipantId,    // ← new
  lastReadAt: Long
) extends Notice
```

If `target` already encodes `(conversationId, participantId)`
internally and the consumer can decode it: document that and
provide a public accessor. Either way the consumer needs *some*
way to derive the participantId from the delta alone.

**Cross-refs:**

- Originating bug: #62 (read-receipt signals introduction).
- Sister bug from the same Sage migration session: #61 (reactions
  signal). #61 ships `participantId` correctly on `Reaction` —
  the receipt-side delta is the asymmetry.
- Sage's tome implementation flagging the gap:
  `sage/tome/lib/chat/sigil_chat_controller.dart` (`_handleSignal`'s
  `case ReadStateDelta d:` arm).
- Sage's reactions+receipts spec:
  `sage/tome/docs/reactions-and-receipts-spec.md`.
