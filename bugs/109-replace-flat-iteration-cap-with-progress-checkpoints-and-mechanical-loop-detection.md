# ❌ #109 — Replace flat `maxAgentIterations` with periodic LLM progress checkpoints + mechanical loop detection

**Where:** `core/src/main/scala/sigil/Sigil.scala`'s
`runAgentLoop` (around the `iteration < maxAgentIterations`
guard) and a new progress-checkpoint component slot.

**What's wrong:** `maxAgentIterations` is a flat cap (10 in
Sigil default; 25 in Sage today). Two failure modes:

  1. **Cap too low.** Genuinely complex tasks — multi-file
     security audits, refactors touching 5+ files, deep
     evaluations — realistically need 30-60 productive
     iterations. They die at the cap mid-work.
  2. **Cap too high.** Runaway loops (per #81, #87, #103)
     burn 30-50 iterations on `find_capability` flailing
     before the cap kills them. The user pays for the
     wasted calls and waits longer for nothing useful.

A flat cap can't distinguish "doing real work" from "stuck."

**Suggested fix:** Replace the flat cap with two
complementary mechanisms:

### 1. Mechanical fast-fail for obvious loops

Track the last N tool-call records. Fast-fail when:

  - 3+ consecutive `find_capability` calls with no other
    tool executions between them (the user's #81 pattern)
  - 2+ consecutive identical (toolName + args-hash) calls
    (parallel-hedge or retry loops)
  - 4+ consecutive `change_mode` calls (the mode-thrash
    pattern from #103)

When detected, the framework injects a system reminder:
*"You've called `find_capability` 3 times in a row without
acting on the results. Pick the rank-1 tool from your last
result and invoke it, or stop and ask the user for clarity."*
If the next iteration ALSO loops, hard-fail with a clear
error.

This is cheap (no extra LLM calls) and catches the dumb
loops within 5-10 iterations.

### 2. Periodic LLM progress checkpoint (every N iterations)

Every `progressCheckpointInterval` iterations (default 15),
the framework runs an out-of-band reflection turn. Different
shape from a normal turn — small fast model, locked tool
choice, narrow prompt:

```
You've been working on this task for 15 turns. Look at the
conversation since the user's last request.

Answer in JSON:
{
  "convergingTowardGoal": <bool>,
  "remainingSteps": "<one-line summary of what's left>",
  "estimatedTurnsToFinish": <int>,
  "stuckOn": "<empty if not stuck; otherwise one line about what's blocking>",
  "shouldAskUser": <bool>  // true if you genuinely need user clarification to continue
}
```

The framework reads the JSON:

  - `convergingTowardGoal = true` and `estimatedTurnsToFinish
    < remaining budget` → keep going
  - `convergingTowardGoal = false` or `shouldAskUser = true`
    → emit a synthetic `respond` to the user with
    `stuckOn` text + asking for guidance, and end the turn
  - `estimatedTurnsToFinish > remaining budget` → emit a
    progress update to the user explaining where things are
    + asking whether to continue or pivot

Costs ~1 extra LLM call per 15 iterations. Uses a cheap fast
model (configurable). For a task that legitimately takes 60
turns, that's 4 reflection calls; for a 10-turn task, 0
reflection calls. The overhead is bounded by task length.

### 3. Productive iteration counter (replaces flat cap)

Track two counters:

  - `productiveIterations` — increments on any non-discovery,
    non-mode-change tool execution that produces a non-Failure
    outcome
  - `auxiliaryIterations` — increments on `find_capability`,
    `change_mode`, `classify_topic_shift`, etc.

Hard cap: `productiveIterations < 100` (very generous —
real work has plenty of headroom). Soft cap on
`auxiliaryIterations`: hits trigger the mechanical fast-fail
above.

Default budget:

```scala
def maxProductiveIterations: Int = 100
def maxAuxiliaryConsecutive: Int = 3
def progressCheckpointInterval: Int = 15
```

Apps tighten or relax per their cost / latency tolerance.

### Test

```scala
class IterationBudgetSpec extends AbstractSigilSpec {

  test("complex task with 30 productive iterations runs to completion") {
    val agent = newAgent(maxProductiveIterations = 100, ...)
    runTask("evaluate password reset across 6 files",
            simulatedToolResponses = realisticAuditResponses)
    // Should complete; budget allows it
    assert(taskCompleted)
  }

  test("3 consecutive find_capability with no executions triggers fast-fail") {
    val agent = newAgent()
    simulateAgentLoop(
      "find_capability", "find_capability", "find_capability"
      // no tool executions between
    )
    val nextResponse = nextAgentTurn()
    assert(nextResponse.contains("system reminder") ||
           nextResponse.contains("pick a tool"))
  }

  test("progress checkpoint at iteration 15 with stuckOn=true asks user for guidance") {
    val agent = newAgent(progressCheckpointInterval = 15)
    val task = simulateStuckTask(turns = 15)  // agent says "stuckOn: 'database schema unclear'"
    val finalEvent = task.lastEvent
    assert(finalEvent.isInstanceOf[Respond])
    assert(finalEvent.content.contains("database schema unclear"))
  }
}
```

The combination — fast mechanical catch + periodic reflection
+ generous productive cap — gives both fewer false positives
(complex tasks finish) and fewer false negatives (loops die
fast). And the reflection step itself often improves task
coherence: forcing the agent to articulate "what's left" and
"how close" mid-work is the kind of meta-cognition that
improves output quality independent of the budget question.

Pairs naturally with #108 (structured ErrorContext): tools
report what went wrong; the reflection step lets the agent
synthesize across multiple turns to recognise patterns the
per-tool error doesn't reveal.
