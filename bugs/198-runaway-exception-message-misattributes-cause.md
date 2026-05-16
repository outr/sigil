# ❌ #198 — `AgentRunawayException` message misattributes the failure cause when forced-synthesis was triggered by the no-triggers path, not cap-hit

**Where:**
- `core/src/main/scala/sigil/Sigil.scala:5234-5239` — the `else if (forceResponseSynthesis)` branch inside `runAgentLoop`'s post-drain decision. Throws
  ```scala
  new AgentRunawayException(
    s"Agent ${agent.id.value} hit maxAgentIterations ($maxAgentIterations) " +
      s"in conversation ${conv._id.value} AND the forced-synthesis turn also " +
      s"failed to call `respond`. Check LLM behavior or raise the cap.")
  ```
  whenever `forceResponseSynthesis=true` and `userVisibleSeen=false`.
- The condition that decides whether this branch fires (line 5222) is just
  `forceResponseSynthesis`. It never checks whether `iteration` actually
  reached `maxAgentIterations`.
- `Sigil.scala:5412-5442` (`case false` branch) — the no-triggers recovery
  path. Sets `forceResponseSynthesis = true` and recurses **at any iteration**, including iteration 2.

**What's wrong:**

The message in the throw is hardcoded as
"hit maxAgentIterations ($maxAgentIterations)". The `$maxAgentIterations`
interpolation reports the **configured cap**, not the iteration counter's
actual value. So the message *always* reads like a cap-exhaustion
diagnosis even when the cap was nowhere near hit.

Forced-synthesis runs are triggered by two distinct conditions:

1. **Cap-hit** (`Sigil.scala:5342` `case true if !forceResponseSynthesis`)
   — `shouldIterate=true && iteration >= maxAgentIterations`. The agent has
   work to do but is out of budget.
2. **No-triggers** (`Sigil.scala:5412` `case false`) — `shouldIterate=false
   && !userVisibleSeen && !forceResponseSynthesis`. The model returned without
   producing a respond and without leaving anything for the next iteration
   to chase. Can happen on iteration 2 just as easily as iteration 25.

Both paths set `forceResponseSynthesis=true` and recurse. Both, if the
recovery turn also fails, end up at the line 5234 throw. The message
text says "cap-hit then failed" but the trigger may have been
"no-triggers then failed" — a completely different LLM failure mode
(model emitted EOS too early under `tool_choice: required`, not "model
took too many iterations").

**Field evidence** — a Sage session this morning:

| Iter | Outcome                                                              |
|------|----------------------------------------------------------------------|
| 1    | Called `find_capability`, returned matches → recursed normally       |
| 2    | `finish=stop` with no tool_call (Qwen violated `tool_choice: required`) → `case false` fired → recursed with `forceResponseSynthesis=true` |
| 3    | Forced-synthesis ran 243 s, generated 28,756 reasoning tokens, hit context cap with `finish=length`, no tool_call → throws |

Wire log confirms 4 `curate` workflows total — agent reached iteration 4
out of `maxAgentIterations=25`. The error message:

```
AgentRunawayException: Agent sage-agent hit maxAgentIterations (25)
  in conversation sage-default AND the forced-synthesis turn also
  failed to call `respond`. Check LLM behavior or raise the cap.
```

…sent us chasing a 25-iteration loop that never happened. The actual
failure was Qwen returning early on iteration 2; the cap was irrelevant.

**Why this matters:**

The error message is the primary diagnostic surfaced to the user
(rendered as a Failure-disposition Message in the chat — see #200 for
the multiplicity issue). Misattributing the cause:

- Leads consumers (Sage in this case) to investigate the wrong thing
  ("did we really run 25 iterations? raise the cap?") when the real
  bug is upstream model misbehavior.
- "Raise the cap" is actively bad advice when the cap wasn't the
  problem — raising it just gives the broken model more rope.
- Wastes downstream diagnostic time tracing iteration counters that
  the message itself implied were the culprit.

**Suggested fix:**

Two scopes — minimum and ideal.

**Minimum**: split the throw site. The throw branch at 5222 doesn't
know which condition triggered the forced-synthesis (only that it's
running one). Carry that info through.

```scala
// runAgentLoop signature gains:
forceResponseSynthesis: Boolean = false,
forcedReason: Option[ForcedSynthesisReason] = None,

sealed trait ForcedSynthesisReason
object ForcedSynthesisReason:
  /** Iteration counter reached the configured cap. */
  case object CapHit                                 extends ForcedSynthesisReason
  /** Model returned without calling a tool / respond. */
  case object NoToolCall                              extends ForcedSynthesisReason
  /** Progress-checkpoint stall intervention. */
  case object StallIntervention                       extends ForcedSynthesisReason
```

The three recursion sites that set `forceResponseSynthesis=true`
(lines 5325-5335, 5380-5400, 5431-5442) each pass the corresponding
reason. The throw at 5234 then formats appropriately:

```scala
val cause = forcedReason match {
  case Some(ForcedSynthesisReason.CapHit) =>
    s"hit maxAgentIterations ($maxAgentIterations) and the forced-synthesis " +
    s"turn failed to call `respond`"
  case Some(ForcedSynthesisReason.NoToolCall) =>
    s"returned without calling any tool at iteration $iteration despite " +
    s"`tool_choice: required`, and the forced-synthesis recovery turn " +
    s"also failed to call `respond`"
  case Some(ForcedSynthesisReason.StallIntervention) =>
    s"stalled at iteration $iteration (progress-checkpoint intervention) " +
    s"and the forced-synthesis recovery turn also failed to call `respond`"
  case None =>
    s"failed at iteration $iteration in forced-synthesis mode" // shouldn't happen
}
Task.error(new AgentRunawayException(
  s"Agent ${agent.id.value} $cause in conversation ${conv._id.value}. " +
  s"Check LLM behavior" + (if (forcedReason == Some(CapHit)) " or raise the cap." else ".")))
```

**Ideal**: include the actual iteration count in every variant
(`at iteration $iteration of cap $maxAgentIterations`) so consumers
never have to guess.

**Related:** #199 (the upstream cause for the *specific* Qwen failure
in our field repro — local-llama reasoning runaway on the forced-
synthesis call). #200 (the same exception cascades 3× through
recursive handleErrors, multiplying the misleading message).
