# Bugs

Tracking bugs found in Sigil, Spice, Fabric, and related libraries while building a downstream consumer on top of Sigil. Each entry: where it lives, what's wrong, what we observed, suggested fix.

**Status legend:** ⚠️ workaround in place · ❌ open · ✅ fixed.

Numbering preserves history — gaps reflect entries that were filed, fixed, and pruned.

---

## ❌ #37 — Orphan `ToolInvoke(state=Active)` recurs after `create_script_tool` flow; bug #33's fix doesn't cover the second `find_capability`

**Where:** same code path as #33 — the orchestrator's `ToolCallStart` → `ToolCallComplete` invariant in `core/src/main/scala/sigil/orchestrator/Orchestrator.scala`, but a new failure path that the previous fix didn't catch.

**What's wrong:** the same orphan-ToolInvoke symptom #33 fixed for `Done` / `Error` arms is reappearing in a different scenario — specifically, after `create_script_tool` lands and the agent immediately invokes a second `find_capability`. The second `find_capability` arrives as a `ToolInvoke(state=Active)` and never gets its terminal `ToolDelta`. Sage's chat shows the chip with the placeholder text I added (`(input pending — Sigil delivers tool inputs as one delta at completion)`) and there's no `ToolResults` chip following it.

**What we observed:** end-to-end repro from the screenshot in chat history.

1. Greet → `respond` ✓
2. User: "I'd like to create a tool that when invoked, calls the URL: …"
3. `find_capability` (1st) → 18 schemas ✓
4. `create_script_tool · get_random_dog_image` (with code) ✓
5. `tool result · 3 schema(s)` ✓ (the create result)
6. `find_capability` (2nd) — **stuck Active, no input, no result**

After the next user turn ("Can you get a dog image?"), the same pattern repeats: a fresh `find_capability` arrives as `Active` and never settles. The activity-recency safety net Sage added in `SigilChatController.isAgentBusy` keeps the Stop button from pinning forever, but the chip itself stays in the "input pending" state forever — and the agent loop appears to fail behind the scenes (see #39 below for the related context-overflow that's likely the proximate cause for the 2nd-turn stuck state).

This isn't the `Done` / `Error` arm path #33's fix covered — the agent on the *previous* turn settled cleanly. It's an entry-arm or sequencing issue: a `ToolInvoke` is emitted at the start of a fresh tool call but the run-loop bails before `ToolCallComplete` ever fires, possibly because the model errored out (see #39) on that turn. So the orphan happens at a layer above the `Done` arm — the run-loop never even reaches the provider's `Done` event.

**Suggested fix:** the orphan-cleanup logic from #33 needs to also fire from `runAgentLoop` (or wherever the loop top-level catches a turn-ending exception). Whenever a turn ends — clean, errored, cancelled, or exceptioned — if `state.activeToolInvokeId.isDefined`, emit the synthetic terminal `ToolDelta`. The cleanup belongs in a `.handleErrorWith` / `finally`-shaped wrapper around the per-turn stream consumer, not just inside the `Done` / `Error` ProviderEvent arms — those only fire if the provider stream actually reached completion.

```scala
// roughly in runAgentLoop — wrap the per-turn stream consumer:
.handleErrorWith { t =>
  // turn errored mid-stream: settle any orphan tool call before
  // propagating, so clients see a terminal ToolDelta instead of a
  // forever-Active ToolInvoke.
  state.activeToolInvokeId.toList.foreach { invokeId =>
    publish(ToolDelta(target = invokeId, conversationId = convId,
                      input = None, state = Some(EventState.Complete)))
  }
  state.activeToolInvokeId = None
  state.activeToolName = None
  Task.error(t)
}
```

A regression test that would have caught this: spin up a fake provider that emits `ToolCallStart("foo")` then *throws* (mid-stream exception, no `Done` / `Error` event), run a turn, assert the resulting event log has a terminal `ToolDelta` for the orphan invoke even though the turn errored. The current #33 test only covers the `Done` / `Error` arms.

---

## ❌ #38 — No client-side primitive for listing persisted tools; consumers can't build a "manage your tools" UI without bypassing the Notice protocol

**Where:** new addition. `db.tools` already exists in the framework's `SigilDB`; the missing piece is a wire-side query primitive analogous to `RequestStoredFileList` / `StoredFileListSnapshot`.

**What's wrong:** Sage (and any future Voidcraft surface) wants to surface a "Tools" panel where the user can browse the script tools they've created via `create_script_tool`, view their code, edit them in place via `update_script_tool`, and delete them. Today there's no client-facing way to enumerate `db.tools` — the agent has `list_script_tools` available *inside* its loop, but the UI can't ask the same question over the wire without invoking the agent.

The existing pattern for similar lookups — `RequestStoredFileList` / `StoredFileListSnapshot` for `db.storedFiles`, `RequestConversationList` / `ConversationListSnapshot` for `db.conversations` — should extend to tools.

**Suggested fix:** add a Notice triple keyed off `db.tools`. Probably typed at the level of "filterable subset" since `db.tools` includes both built-in shipped tools (uninteresting to list to the user) and persisted user-authored tools (what the panel actually wants):

```scala
case class RequestToolList(spaces: Option[Set[SpaceId]] = None,
                           kinds: Option[Set[String]] = None) extends Notice derives RW

case class ToolListSnapshot(tools: List[ToolSummary]) extends Notice derives RW

case class ToolSummary(toolId: Id[Tool],
                       name: String,
                       description: String,
                       kind: String,        // e.g. "script", "browser-script", or empty for built-ins
                       spaceId: Option[SpaceId],
                       modifiedMs: Long) derives RW
```

`kinds` filter lets the consumer ask "just script tools" or "just browser scripts" without enumerating built-ins. Default arm in `Sigil.handleNotice` does the equivalent of `accessibleSpaces(fromViewer)`-filtered `db.tools.list` and replies with summaries.

For the management flow (edit / delete), the existing `update_script_tool` / `delete_script_tool` tool inputs already exist; they just need to be invokable from the UI side. Either:
- The UI sends a synthetic Message with a structured "please update tool X to code Y" prompt and the agent invokes the tool (current Sage behaviour for "Send edits to agent" — works but routes through the model).
- Or we add direct client-issued Notices: `UpdateToolDirect` / `DeleteToolDirect` that bypass the agent and just mutate `db.tools`. Lower-cost, but introduces a bypass channel; would want to think about whether that's a precedent we want.

Sage will start with the synthetic-message path for v1 (consistent with how the existing "Send edits to agent" affordance works), but the read-side `RequestToolList` / `ToolListSnapshot` is the prerequisite either way.

**Sage-side once this lands:** new "Tools" tab in the workspace nav rail; controller handler for `ToolListSnapshot`; rows clickable into the editor panel showing the tool's code (using the existing `CodePanel` machinery the script-CRUD chip flow uses today). Maybe ~80 lines of consumer code.

---

## ❌ #39 — Default `Sigil.curate` and `memoryExtractor` are no-ops; agents hit `exceed_context_size_error` after a handful of `find_capability` calls without any framework-driven compression

**Where:** `core/src/main/scala/sigil/Sigil.scala`:
- `def curate(...)` (line 575): defaults to `Task.pure(TurnInput(view))` — no compression, no curation.
- `def memoryExtractor` (line 852): defaults to `NoOpMemoryExtractor` — no per-turn memory extraction.

`StandardContextCurator` and `StandardMemoryExtractor` exist in the framework but aren't wired as defaults.

**What's wrong:** out of the box, Sigil sends the entire conversation event log to the model on every turn. `find_capability` results are huge — each match is a full tool schema with input definitions, examples, and descriptions; 15–18 schemas (Sage's typical roster) is easily 15–20K tokens worth of context per call. Stack two `find_capability` calls plus normal back-and-forth and you exceed a 32K-context model in 3–4 turns.

`find_capability`'s tool description says results are valid for ONE next turn ("Matches are valid for ONE next turn — call the matched tool then, or it's cleared"). But the cleared-after-one-turn semantics applies to the agent's *discovery state* (what tools it can name in `toolNames`), not to the conversation's event log — the `ToolResults` carrying those 18 schemas stays in `db.events` and gets resurfaced into the model's context every subsequent turn. So the schemas are present in context far longer than they're useful.

**What we observed:** the conversation in the screenshot:

1. Greet (~50 tokens)
2. User msg (~50 tokens)
3. `find_capability` (1st) → 18 schemas (~18K tokens)
4. `create_script_tool` invocation with code (~500 tokens)
5. `tool result · 3 schema(s)` (~3K tokens)
6. `find_capability` (2nd) → orphan, but its predecessor's 18 schemas are still in the event log

Then on the next user turn ("Can you get a dog image?"), Sigil tries to send the whole history and llama.cpp returns:

```
HTTP 400: request (33254 tokens) exceeds the available context size (32768 tokens)
```

`runAgentLoop` propagates the error and the turn fails — likely the proximate cause of the orphan-state described in #37.

This isn't a config error. It's the default behaviour with the default `Instructions` / `coreToolNames` / a normal-shaped local model. Every consumer using the defaults is going to hit this on their second or third real-work turn.

**Suggested fix:** two pieces.

1. **Switch the framework defaults to compressing curators.** Default `curate` should be `StandardContextCurator(this).curate(view, modelId, chain)` (or whatever the right `apply` shape is); default `memoryExtractor` should be `StandardMemoryExtractor` (already exists). Apps that explicitly want no compression override to `Task.pure(TurnInput(view))` / `NoOpMemoryExtractor` — but the safe default is "compress, don't blow up." Currently the unsafe default is "blow up at a few thousand tokens of accumulated tool output."

2. **Drop expired `find_capability` results from the curated view.** The tool already establishes the "valid for one turn" contract — the curator should honour it. After a `find_capability`'s `ToolResults` is more than one turn old, the curator should either drop it from the prompt entirely or replace it with a tiny placeholder (`(find_capability results elided — call again to re-search)`). Same idea for any other "ephemeral discovery" tool added later. This alone would cut the offending conversation from 33K tokens down to ~600 just by elision.

Pushing through to context-overflow as a hard error is the wrong posture for a framework whose whole job is keeping a conversation going. The framework shouldn't need consumer config to keep the lights on under normal usage.

A regression test on (1): fixture conversation with 30 `find_capability` calls (plenty enough to overflow any reasonable context), default `Sigil` instance, run a turn, assert the curated `TurnInput` size is bounded — say under 8K tokens regardless of history length. On (2): fixture with one `find_capability` call ten turns back, assert the curated view doesn't contain its 18 schemas anymore.

---

These three are intertwined: #39's overflow is what crashes the agent loop mid-tool-call, leaving #37's orphan ToolInvoke behind. The user-visible "input pending" placeholder Sage renders for the orphan is what surfaces the problem. Fixing #39 likely makes #37 much rarer (turns won't be aborting mid-call), but the orphan-cleanup hardening from #37's fix is still worth doing for the residual cases (provider connection drops, etc.). #38 is independent — it's the "let users see what tools they have" UI primitive Sage needs regardless.
