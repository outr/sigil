# Bugs

Tracking bugs found in Sigil, Spice, Fabric, and related libraries while building a downstream consumer on top of Sigil. Each entry: where it lives, what's wrong, what we observed, suggested fix.

**Status legend:** ⚠️ workaround in place · ❌ open · ✅ fixed.

## Sigil

### 1. ✅ "Greet on join" / wake-on-empty-conversation hook for agents

**Where:** `core/src/main/scala/sigil/{role/Role.scala, participant/AgentParticipant.scala, Sigil.scala}`.

**Symptom (was):** A consumer opens a fresh conversation with one `AgentParticipant`; nothing happens until the user types. Late-joining an agent into an existing conversation had the same gap.

**Fix landed:**

- `AgentParticipant.greetsOnJoin: Boolean = false` — agent-level lifecycle flag. (Was originally landed per-`Behavior`; moved up to the agent during the Behavior→Role merged-dispatch refactor since greeting is a once-per-join lifecycle event, not a per-role concern.)
- `AgentParticipant.processGreeting(ctx)` — final method; runs the same merged `Sigil.process(this, ctx, Stream.empty)` dispatch as a normal turn. The agent's roles' descriptions and skills drive the greeting wording.
- `Sigil.fireGreeting(agent, conv): Task[Unit]` — public hook; no-op when `agent.greetsOnJoin == false`. Claims the AgentState lock and runs the agent loop with `greeting = true` so iteration 1 calls `processGreeting` instead of `process`. Subsequent iterations (driven by non-terminal tool calls) revert to the standard process path.
- `Sigil.newConversation` walks `participants` after persistence and calls `fireGreeting` for each `AgentParticipant`.
- `Sigil.addParticipant(conversationId, participant)` — new conversation-management API for late-join; persists the appended list, then calls `fireGreeting` if the participant is an agent. Idempotent on re-add.
- `Sigil.removeParticipant`, `Sigil.deleteConversation` — added alongside for symmetry; the conversation-CRUD surface is now `create/addParticipant/removeParticipant/delete`.

Test coverage: `core/src/test/scala/spec/GreetOnJoinSpec.scala` exercises fresh-greet, late-join greet, multi-role agents firing exactly once (one merged dispatch regardless of role count), no-greet path, and addParticipant idempotency. Uses a no-op stub provider so assertions hit the dispatcher's lifecycle signals directly.
