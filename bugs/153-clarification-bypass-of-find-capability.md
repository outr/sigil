# ❌ #153 — Agent bypasses `find_capability` discovery by asking the user for clarification on action-shaped requests

**Where:**
- `core/src/main/scala/sigil/provider/Instructions.scala` — the system prompt that mandates discovery-first
- `core/src/main/scala/sigil/orchestrator/Orchestrator.scala` (around the `refusalChallengeOutcome` path landed for bug #126) — the existing refusal-challenge intercept

**What's wrong:**

The framework's instructions explicitly mandate discovery-first for action-shaped user messages: *"check the available-modes list ... If ANY listed mode clearly matches the task, call `change_mode` FIRST — do NOT call `find_capability`. ... Almost every action the user asks for has a dedicated tool — your job is to find it, not to fake it through `respond`."*

Bug #126 added a refusal-challenge intercept: when the agent's `respond` reads as a refusal AND no `find_capability` was called since the last user message, the framework suppresses the respond and injects a `_refusal_challenge` Tool-role Failure so the agent re-runs with explicit instructions to consult the catalog first. That fix works for explicit refusals ("I can't switch models", "I'm not able to", etc.).

But the agent has found a loophole: **clarification-asking** sidesteps the check entirely. Observed in a Sage wire log:

```
User message:  "Switch to medium complexity"
Agent action:  immediate respond  (zero find_capability calls preceding)
Agent content:
  "I need to clarify what you mean by 'medium complexity.' Could you
   specify what you'd like me to adjust or work on with medium
   complexity? For example:
     - A coding task you want me to tackle?
     - A configuration setting?
     - Something else?
   I want to make sure I understand your request correctly before
   taking action."
```

The phrasing doesn't trigger #126's refusal detector — there's no "I can't", no "unable to", no "I'm sorry but". It reads as a polite clarification request. Yet "Switch to medium complexity" is a clearly action-shaped request: a `pin_complexity` tool exists (#152), `find_capability("change complexity tier")` would have surfaced it. The instructions exist; the agent ignored them.

The full agent reply is structurally identical to a refusal — "I am NOT going to do the thing the user asked, and instead I'm going to send a response message" — but with softer language. From the user's perspective, the outcome is the same: the action wasn't taken, the agent didn't even *try* to find a capability, and they're stuck explaining what they meant.

**Suggested fix:**

Extend the refusal-challenge intercept (or rename to "non-action-respond challenge") to fire on any `respond` that:

1. Has `endsTurn = true` AND no `find_capability` / `change_mode` / non-`respond` tool call has been made since the last user message
2. AND the prior user message reads as action-shaped (heuristic: contains a verb like "switch", "change", "set", "make", "run", "do", "find", "show", "open", "start", etc.)

Trigger the same `_refusal_challenge` Tool-role Failure shape #126 already builds, with adjusted text:

```
The user's message was action-shaped ("Switch to medium complexity").
You responded with clarification-asking before calling `find_capability`.
That violates the discovery-first rule. Re-run this turn:
  1. Call `find_capability("change complexity tier")` (or similar) first.
  2. If a tool exists, call it.
  3. Only if find_capability returns nothing should you ask the user
     for clarification, and that respond should explicitly state what
     was searched ("I looked for 'X' and 'Y' and didn't find a matching
     tool; could you describe what you want differently?").
```

The loop safety from #126 carries forward: only challenge once per user turn. If the agent re-emits a clarification respond after the reminder, the respond passes through (the clarification IS legitimate).

**Severity:** UX-critical. The discovery-first rule is the framework's stated core ideology, but the current intercept only enforces it against explicit refusals. Clarification-asking is the polite-language version of the same anti-pattern: the agent skipped discovery, declined to take action, and shifted the burden back to the user. Both should be challenged. Today's loop-hole means any user request the agent finds even slightly ambiguous gets bounced back as a clarification request without consulting the catalog — which is exactly the failure mode the framework's whole tool-discovery architecture was designed to prevent.

This is a prevention fix — once landed, the agent's only path to a non-action respond on an action-shaped request goes through `find_capability` first, and the respond it eventually writes will reflect the genuine outcome (tool called, OR no matching tool found).
