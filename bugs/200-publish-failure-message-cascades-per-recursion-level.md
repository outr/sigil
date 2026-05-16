# ❌ #200 — `publishFailureMessage` fires once per recursion level of `runAgentLoop`, surfacing N identical failure bubbles for one failed turn

**Where:**
- `core/src/main/scala/sigil/Sigil.scala:5446-5458` — the top-level
  `.handleError` attached to every `runAgentLoop` invocation:
  ```scala
  }.handleError { t =>
    scribe.error(s"runAgent failed for ${agent.id.value} in ${convId.value}", t)
    publishFailureMessage(agent, convId, t).handleError(_ => Task.unit)
      .flatMap(_ => terminate().handleError(_ => Task.unit))
      .flatMap(_ => Task.error(t))   // ← re-throw propagates to parent recursion's handler
  }
  ```
- `core/src/main/scala/sigil/Sigil.scala:5809-5834` —
  `publishFailureMessage`, which writes a single
  `Message(disposition = Failure(...))` per call.
- `core/src/main/scala/sigil/Sigil.scala:5040-5047` — entry point
  pattern that already threads a CAS-guarded `AtomicBoolean`
  (`turnExtractorFired`) through every recursion to give
  fire-once semantics. The same shape is exactly what's needed
  here.

**What's wrong:**

`runAgentLoop` is recursive — each iteration is a fresh invocation
of the same `Task.defer { ... }.handleError { ... }` wrapper.
That means **every recursion level has its own `.handleError`**
attached to its own slice of the call stack.

When the inner-most level throws (e.g. the forced-synthesis
turn's `AgentRunawayException`), the cascade is:

1. Inner-most handler catches → `publishFailureMessage` writes
   user-visible Message #1 → `Task.error(t)` re-throws the
   original `t`.
2. Re-thrown `t` propagates up the stack until it hits the next
   recursion level's `.handleError`. That handler catches →
   `publishFailureMessage` writes Message #2 → re-throws.
3. Repeat for every recursion level above.

Result: **N identical failure Messages in the chat for a single
turn that failed once**, where N is the recursion depth at the
moment of throw.

**Field evidence** — a Sage session this morning, three turns of
recursion deep when `AgentRunawayException` fired from
forced-synthesis:

```
events.jsonl @ 10:09:47:
  AgentStateDelta            activity=Idle  state=Complete
  Message     role=Standard  disp=Failure   text="AgentRunawayException: Agent sage-agent hit maxAgentIterations (25) ..."
  AgentStateDelta            activity=Idle  state=Complete
  Message     role=Standard  disp=Failure   text="AgentRunawayException: Agent sage-agent hit maxAgentIterations (25) ..."
  AgentStateDelta            activity=Idle  state=Complete
  Message     role=Standard  disp=Failure   text="AgentRunawayException: Agent sage-agent hit maxAgentIterations (25) ..."
  AgentStateDelta            activity=Idle  state=Complete
```

Three identical failure Messages with byte-identical text,
each wrapped in an `Idle/Complete` AgentStateDelta. The chat
client (Sage) faithfully rendered three failure bubbles.

The recursion depth at the throw matched the iteration count of
the failed turn:

| Recursion | Iteration shape                          | Outcome                              |
|-----------|------------------------------------------|--------------------------------------|
| Outer-1   | First inference (called find_capability) | recursed normally                    |
| Mid-2     | Post-capability (16 tools offered)       | `finish=stop` → recursed as forced-synth |
| Inner-3   | Forced-synthesis (3 tools offered)       | 4-min reasoning runaway → throws     |

3 levels → 3 published failures → 3 chat bubbles.

**Why this matters:**

Each duplicate Message is a separate persisted Event, broadcast
over the WS to every viewer, rendered in the chat list, and
later included in the conversation's context for memory extraction
and curate. The cost compounds:

- **User confusion** — three identical bubbles for the same error
  reads like the framework looped on the failure rather than
  reporting it once.
- **Forensic noise** — wire logs and `events.jsonl` carry N copies
  of the same payload. Downstream tooling (`#175`-style reconciler,
  turn-token chip, etc.) treats them as distinct events for
  counting purposes.
- **Future memory pollution** — three Failure Messages in the
  same turn's window will be visible to the per-turn memory
  extractor (#149's flow). If the extractor ever decides "this
  turn failed badly" via signal-counting, it'll triple-count.

The corresponding `terminate()` calls (which also fire per level)
are idempotent in their effect (release the claim, fire the
extractor's CAS — both no-op on second+ calls), so the chat-side
visible damage is concentrated specifically in the duplicated
user-visible Messages.

**Suggested fix:**

Same shape as `turnExtractorFired` (`Sigil.scala:5047`) — thread
a CAS-guarded `AtomicBoolean` through every recursion of
`runAgentLoop` so the inner-most handler wins the publish and
outer handlers re-throw silently.

```scala
// At the entry point (around line 5040):
runAgentLoop(
  ...,
  turnExtractorFired = new java.util.concurrent.atomic.AtomicBoolean(false),
  failurePublished   = new java.util.concurrent.atomic.AtomicBoolean(false)
)

// Threaded through every recursion site (lines 5325-5335, 5338-5339,
// 5380-5400, 5431-5442). Same pattern as turnExtractorFired.

// In the handleError at 5446:
}.handleError { t =>
  scribe.error(s"runAgent failed for ${agent.id.value} in ${convId.value}", t)
  val publishOnce =
    if (failurePublished.compareAndSet(false, true))
      publishFailureMessage(agent, convId, t).handleError(_ => Task.unit)
    else
      Task.unit
  publishOnce
    .flatMap(_ => terminate().handleError(_ => Task.unit))
    .flatMap(_ => Task.error(t))
}
```

Notes on what stays per-level vs. fire-once:

- `scribe.error` stays per-level. The logged stack trace shape
  changes at each level (different `<flatMap>` frames depending
  on where the recursion was), and per-level logs help diagnose
  *which* recursion level the throw originated at. Operator-facing
  cost is negligible (one extra log line per level); not user-
  visible.
- `terminate()` stays per-level. It's already idempotent
  (releases the claim — second call no-ops because the claim is
  gone; fires the extractor's own CAS — second call no-ops).
- `Task.error(t)` stays per-level — re-throwing is what carries
  the failure up the stack to the calling fiber's outer boundary.
- `publishFailureMessage` becomes single-shot. The inner-most
  level wins; outer levels see `failurePublished=true` and skip.

The CAS guarantees correctness even if two levels race on the
publish (shouldn't happen in practice — `runAgentLoop` is
sequential — but cheap insurance).

**Related:** #198 (the misleading error message that gets
multiplied), #199 (the underlying cause of *this* throw in the
field repro). Together with #198 they form a "diagnostics
hygiene" trio — even a perfectly-attributed error message (#198)
becomes confusing when it's printed three times (#200), even
without the underlying model misbehavior (#199).
