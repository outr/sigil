# ❌ #109 — Replace flat `maxAgentIterations` with delta-based LLM progress checkpoints

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

**Suggested fix:** Replace the flat cap with delta-based
LLM progress checkpoints. Don't add mechanical loop
detection — multiple `find_capability` calls with different
keywords is legitimate exploration (the agent trying keyword
variants to find a tool the discovery index doesn't surface
on the first query). A mechanical "3 consecutive
find_capability" rule would kill perfectly reasonable
exploration. The LLM checkpoint can read the same history
and judge in context: are these productive keyword variations
that converge, or repetitive churn? It can tell; mechanical
heuristics can't.

The single mechanism: periodic LLM checkpoint that compares
*against the prior checkpoint's status*, so loops surface as
"my status is the same as last time" — not as "I called
find_capability N times."

### Delta-based LLM progress checkpoint (every N iterations)

Every `progressCheckpointInterval` iterations (default 15),
the framework runs an out-of-band reflection turn that
references the PRIOR checkpoint's status as the comparison
baseline. The agent doesn't just self-assess in isolation;
it answers "what's changed *since the last reference point*?"

This is the key design choice: a chain of checkpoints, each
one anchored to its predecessor. Repetition of status text
across checkpoints becomes the loop signal — the agent can't
fake "yes I'm making progress" without articulating
something concrete that has changed.

#### Persisted checkpoint state

A new `ProgressCheckpoint` event in the conversation:

```scala
case class ProgressCheckpoint(
  conversationId: Id[Conversation],
  iterationCount: Int,                    // how far into the turn
  prevCheckpointStatus: Option[String],   // the X — prior status, or None for the first checkpoint
  currentStatus: String,                  // the new short summary
  meaningfulProgress: Boolean,            // agent's verdict on whether things changed
  remainingSteps: String,                 // one-line summary of what's left
  stuckOn: Option[String],                // populated if loop / blocker detected
  shouldAskUser: Boolean,                 // user input required to proceed
  timestamp: Timestamp
) extends Event
```

Each checkpoint carries the prior `currentStatus` as
`prevCheckpointStatus`, so the chain is reconstructable from
the event log.

#### Reflection prompt

```
You're {iterationCount} turns into this task.

{if prevCheckpointStatus.isDefined:
  At iteration {prevCheckpointIter}, you said:
  "{prevCheckpointStatus}"
}

Look at what you've done since {if prev: that point else: the start}. Answer:

{
  "currentStatus": "<one-line summary of where things stand RIGHT NOW>",
  "meaningfulProgress": <bool — are you in a substantively different place than the prior status, or just churning>,
  "remainingSteps": "<one-line summary of what's left to finish the user's request>",
  "stuckOn": "<empty if making progress; otherwise one line about what's blocking>",
  "shouldAskUser": <bool — true if you genuinely need user clarification to continue>
}

Be honest. If your status looks identical to the prior status
or you're cycling through the same searches, say so —
"meaningfulProgress: false" so the framework can intervene.
```

#### Framework response to checkpoint

The framework reads the JSON and decides:

  - `meaningfulProgress = true` and `stuckOn` empty → keep
    going. Persist the checkpoint, continue the loop.
  - `meaningfulProgress = false` for the SECOND checkpoint
    in a row → loop detected. Emit a synthetic `respond` to
    the user: *"I've been working on this for {N} turns and
    haven't made meaningful progress since {prevStatus}. I'm
    stuck on: {stuckOn}. How would you like me to proceed?"*
  - `shouldAskUser = true` → emit `respond` asking for
    guidance, end the turn.
  - `meaningfulProgress = true` for many checkpoints in a row
    but iteration count exceeds expected complexity → emit
    a progress update to the user (visible chip / inline
    message) showing the chain of statuses so the user can
    see where the agent has been and decide whether to let
    it continue.

The "two consecutive non-progress checkpoints" rule is what
catches subtle loops — the kind where the agent IS making
small changes turn-by-turn but the overall status hasn't
moved (e.g., reading file after file but never converging on
an answer).

#### UX win

The chain of `ProgressCheckpoint` events is a natural artifact
the chat UI can render — a thin vertical "where the agent has
been" timeline with each checkpoint's status. The user can
glance at it and immediately see whether the agent is
exploring or thrashing, without reading every individual
tool call.

#### Cost

~1 extra LLM call per 15 iterations, configurable. Cheap
small model fine for the reflection (the agent doesn't need
its full power to summarize what it just did). For a task
that legitimately takes 60 turns, 4 reflection calls. For a
10-turn task, 0 reflection calls. The overhead is bounded
by task length.

### Hard absolute ceiling (defence in depth)

The checkpoint is the primary mechanism, but a hard absolute
ceiling stays in place as a runaway-cost safety net. Default
generous: 200 iterations. Apps that want strict cost control
tighten it; apps that want unbounded long-running tasks
raise it. The checkpoint should fire well before this in
real usage; it's only there to prevent pathological
infinite loops if the checkpoint itself misbehaves.

```scala
def progressCheckpointInterval: Int = 15
def maxAgentIterations: Int = 200          // hard ceiling, generous
def consecutiveNoProgressLimit: Int = 2    // checkpoints saying "no progress" before stopping
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

  test("multiple find_capability with different keywords does NOT fast-fail (legitimate exploration)") {
    val agent = newAgent()
    simulateAgentLoop(
      ("find_capability", """{"keywords":"read file"}"""),
      ("find_capability", """{"keywords":"grep ripgrep list"}"""),
      ("find_capability", """{"keywords":"workspace file tree"}""")
    )
    // Each query is different — agent is genuinely exploring keyword variants.
    // The framework should NOT short-circuit; let the agent keep looking.
    // Only the checkpoint at iteration 15 will judge whether progress was made overall.
    assert(agent.stillRunning)
  }

  test("two consecutive 'no meaningful progress' checkpoints triggers user-prompt") {
    val agent = newAgent(progressCheckpointInterval = 5)
    simulateStuckTaskWithCheckpoints(
      checkpoint1 = ProgressCheckpoint(meaningfulProgress = false, stuckOn = "can't find right tool"),
      checkpoint2 = ProgressCheckpoint(meaningfulProgress = false, stuckOn = "still can't find right tool")
    )
    val finalEvent = agent.lastEvent
    assert(finalEvent.isInstanceOf[Respond])
    assert(finalEvent.content.contains("haven't made meaningful progress"))
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
