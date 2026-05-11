# ❌ #138 — `find_capability` results aren't carried forward to subsequent agent turns; agent re-discovers the same tools in a loop

**Where:**
- `core/src/main/scala/sigil/Sigil.scala:2612-2618` — `applyParticipantProjectionFor(CapabilityResults)` overwrites `suggestedTools` with the latest result's Tool matches. The list lives for **exactly one turn** — the next turn (with no new `find_capability` call) finds it empty (per `Sigil.scala:4880` clear logic).
- `core/src/main/scala/sigil/conversation/ParticipantProjection.scala` — `recentTools` exists and lasts longer than one turn, but it's a list of `ToolName`s the agent has *invoked*, not the result set of `find_capability` queries. Distinct concept.

**What's wrong:**

`find_capability` is supposed to be the discovery primitive that surfaces tools the agent doesn't have in its default roster. The framework's expectation (per `Instructions.autonomous`): agent calls `find_capability("X")`, gets back the matching tools, then invokes one of them on the next turn from the suggestions list. But the suggestion list **decays after a single turn** — so if the agent doesn't immediately invoke the discovered tool, the discovery is wasted and must be repeated.

In practice on weaker / smaller models, the agent often does:
1. Turn N: `find_capability("read")` → returns `[read_file, grep, ...]`
2. Turn N+1: invokes `read_file` — suggestion list still alive
3. Turn N+2: needs to read another file → list cleared because N+1 didn't re-call find_capability → agent calls `find_capability("read")` again
4. (repeats indefinitely)

**Real cost evidence** (Sage session, 2026-05-11):

The agent invoked `find_capability("view file source contents read code lines")` **15 consecutive times** across 9 minutes of work. Identical query string, identical match list returned each time. Each call cost $0.02-$0.12 depending on the conversation transcript size at that point. **Total wasted: ~$0.41 — 39% of the entire $1.057 session cost.**

The agent's instinct ("I need read_file, let me search for it") was correct. The framework dropped the prior 14 successful searches off the projection and forced re-discovery each time.

**Why `recentTools` doesn't fix this:**

`recentTools` is updated on `ToolInvoke` (per `Sigil.scala:2604-2607`) — the list of tool names the agent has *already called*. It surfaces in the system prompt's `WireRequestProfile.recentTools` section. Two problems:

1. It contains the agent's history, not the agent's *available roster*. An agent that's never invoked `read_file` won't have it in `recentTools` even if a recent `find_capability` returned it.
2. The agent's discovery prompt doesn't consult `recentTools` to decide whether to re-search. The model has to be told "you already searched for this" via the prompt; the prompt doesn't currently say so.

**Test first:**

```scala
class FindCapabilityResultPersistenceSpec extends AsyncWordSpec with Matchers {
  "Agent invoking find_capability twice with the same keywords" should {
    "see the second call resolve from cache without a new framework dispatch" in {
      // Drive find_capability("foo bar") on turn N. Drive the same
      // call on turn N+3. Assert turn N+3 does NOT invoke
      // `context.sigil.findCapabilities` (the underlying matcher);
      // the projection should already carry the result and surface
      // it without a re-dispatch.
    }

    "expire cached results after a configurable TTL (default 50 turns)" in {
      // Cache must not be infinite — stale matches age out so the
      // agent re-discovers eventually when the registry's truly
      // changed (e.g. new MCP tools added mid-conversation).
    }

    "invalidate cache when a McpServer registration changes" in {
      // McpServerAdded / McpServerRemoved fires → invalidate the
      // affected cache entries (any entry whose match list contained
      // tools from the affected server).
    }
  }

  "ParticipantProjection.discoveredCapabilities" should {
    "surface in the system prompt so the agent sees what's already been discovered" in {
      // Drive find_capability("read"). Drive a subsequent turn.
      // Inspect the WireRequestProfile / prompt assembly for that turn.
      // Assert the prompt contains a "Capabilities you've already
      // discovered this conversation: read_file, grep, ..." section,
      // sourced from the persisted discoveredCapabilities.
    }
  }
}
```

All four must fail on current `main`.

**Suggested fix:**

Two layers — a structural cache + an agent-instruction surface so the model knows to consult it.

### 1. `ParticipantProjection.discoveredCapabilities`

Add a new field that accumulates `find_capability` results across the conversation:

```scala
case class ParticipantProjection(
  // ... existing fields ...

  /** Map from normalized query keywords → CapabilityMatch list, with
    * timestamp. Populated by `applyParticipantProjectionFor` on
    * every `CapabilityResults` event. Cleared by `discoveredCapabilities = Map.empty`
    * resets (TTL expiry, conversation reset). The agent's system
    * prompt surfaces a flat union of all match lists so it sees the
    * full set of tools it has access to. */
  discoveredCapabilities: Map[String, DiscoveredCapability] = Map.empty
)

case class DiscoveredCapability(
  matches:   List[CapabilityMatch],
  firstSeen: Timestamp,
  lastSeen:  Timestamp
)
```

`applyParticipantProjectionFor(CapabilityResults)` writes to this map (keyed on normalized query keywords) AND to `suggestedTools` (today's one-turn behavior, kept for backwards-compat).

### 2. System-prompt surface

Extend the `WireRequestProfile` rendering (the per-turn prompt-assembly section) to include a `## Capabilities you've already discovered` block when `discoveredCapabilities` is non-empty:

```text
== Capabilities you've already discovered (this conversation) ==
- read_file (file, read, open, contents)  — discovered 3 turns ago
- grep (search, find, pattern, text)  — discovered 5 turns ago
- glob (list, files, directory)  — discovered 5 turns ago

If your current task needs one of these, invoke it directly. Do NOT
re-search via find_capability for tools you've already discovered
this conversation.
```

This gives the model the signal it needs to suppress the rediscovery loop without changing its discovery instinct ("search when uncertain"). It just learns that uncertainty about already-discovered tools is misplaced.

### 3. Cache TTL + invalidation

- **TTL**: default 50 turns. Configurable per app via `Sigil.discoveredCapabilityTtl`. Long enough that mid-conversation tasks share the cache; short enough that very long sessions eventually re-validate.
- **Invalidation hooks**: on `McpServerAdded`, `McpServerRemoved`, or any framework event that mutates the tool registry, clear entries whose matches reference the affected server. On `change_mode`, clear entries whose matches were mode-scoped (per #122 — non-active-mode tools are gated as `RequiresSetup`; a mode change can flip their status).

### 4. `Tool` call shortcut

Optional: when the agent calls `find_capability("...")` with keywords that match an existing cached query (or significant subset overlap), the framework can short-circuit and return the cached result without re-running the matcher. Even cheaper than letting the model figure out it shouldn't re-search.

**Cost estimate**: applied to the cited Sage session, the rediscovery loop alone was $0.41. With the cache + prompt surface, the agent would have invoked `read_file` directly after the first discovery and saved ~$0.39 (one initial discovery cost stays).

**Composes with:**

- **#129** (find_capability instruction keyword examples) — together they shape the agent's discovery behavior: #129 teaches HOW to construct effective queries; #138 teaches NOT to repeat them.
- **#122** (mode-affinity gate) — invalidation hook on `change_mode` keeps cached matches accurate when the available roster changes.
- **#136** (previous_response_id) — surfacing `discoveredCapabilities` in the prompt only matters if the prompt isn't being reshipped from scratch each turn. The two land together for compounded savings.

**Bottom line**: the model isn't doing anything wrong — Sigil's projection doesn't carry the discovery forward, and the prompt doesn't tell the model it should reuse what it already found. Persisting + surfacing is a Sigil-layer fix; once it's in, the per-conversation rediscovery loops disappear without touching the model's instruction prompt at all.
