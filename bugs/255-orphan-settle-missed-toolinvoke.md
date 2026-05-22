# ❌ #255 — orphan-settle path marks a ToolInvoke settled without emitting its paired ToolResult

**Where:**

- `core/src/main/scala/sigil/orchestrator/Orchestrator.scala` — the
  orphan-settle path / "corruption-resistance invariant" that is supposed to
  emit a paired `Tool`-role Message for every `ToolInvoke` before a turn is
  rendered.
- `core/src/main/scala/sigil/provider/Provider.scala` — `renderFrames`
  (~line 1398), where the dangling tool_call is detected.

**What's wrong:**

`renderFrames` logged an ERROR for a `ToolInvoke` that reached rendering with
no paired `ToolResult`:

```
ERROR Provider.renderFrames:1398
renderInput: dangling tool_call wireId=functions.find_capability:6 has no
paired ToolResult in this turn's frame trail. The orchestrator's
corruption-resistance invariant should have emitted a paired Tool-role
Message before this turn was rendered.
  invokes seen:  respond:0, list_products:1, find_capability:2,
                 update_product:3, find_capability:4, list_products:5,
                 find_capability:6, <respond>, update_product:7,
                 find_capability:8
  results seen:  list_products:1, find_capability:2, update_product:3,
                 find_capability:4, list_products:5, <respond>,
                 update_product:7, find_capability:8
  invokes settled: 10
  invokes active:  0
Emitting a diagnostic function_call_output marker to keep the wire shape
valid; investigate why the orphan-settle path missed this invoke.
```

`find_capability:6` is the dangling invoke. (`respond:0` is also unpaired,
but that is expected — `respond` is an atomic-content tool.) `find_capability`
is **not** an atomic-content tool, so it must produce a `Tool`-role result —
and the turn's other three `find_capability` invokes (`:2`, `:4`, `:8`) all
got results. Only `:6` was missed.

Critically: `invokes settled: 10` — all ten invokes, including `:6`, are
marked **settled**, yet `:6` produced no result Message. So the settle was
recorded without the paired result Event being emitted — "settled" and "result
emitted" are not atomic.

**Suggested fix:**

Marking a `ToolInvoke` settled must atomically co-emit its paired `Tool`-role
result Message (or, for atomic-content tools, the synthetic empty output).
Investigate the path where `find_capability:6` was recorded settled but
produced no result Event — likely a race or an early-return that bumps the
settled count while skipping result emission. `renderFrames`'s diagnostic
marker self-heals the wire shape, so the turn survives, but every occurrence
is a real invariant violation and logs ERROR-level noise.

**Severity:** Medium — `renderFrames` self-heals (the turn does not hard-fail,
unlike #254's provider 400), but it is a genuine hole in the
corruption-resistance invariant. Same tool-call / result-pairing family as
#254 — there a `function_call_output` had no `function_call`; here a
`function_call` (ToolInvoke) has no `function_call_output` (ToolResult).
