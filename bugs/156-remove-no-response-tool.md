# ❌ #156 — Remove `NoResponseTool` from core; always require a real response

**Where:**
- `core/src/main/scala/sigil/tool/core/NoResponseTool.scala` — the tool itself
- `core/src/main/scala/sigil/tool/core/CoreTools.scala` — `all` vector + `coreToolNames` include it; every agent that ships with `CoreTools.coreToolNames` carries it always-on

**What's wrong:**

`no_response` is a terminal "decline to respond" tool — agent calls it when it has nothing to say, and the framework ends the turn silently. The intent (per its description) is "when the message isn't for you, or no reply is appropriate."

In practice the affordance is almost never useful and frequently misused:

1. **In a single-user-local app (Sage), every user message IS for the agent.** There's no second listener, no multi-party room. "Not for you" is structurally impossible. The tool's premise doesn't apply.

2. **In multi-agent / Slack-relay setups, a no-reply outcome should be a routing-layer decision (sender doesn't match self-id, channel doesn't include this agent), not an agent-loop affordance.** The agent receiving the message and *then* deciding "I won't answer" is the wrong shape — it already paid the LLM round-trip cost; the silence is just a UX failure rolled in expensive paper.

3. **It gives the model an escape hatch from the discovery-first rule.** When the model can't immediately find a fitting tool, "respond with no_response" is a clean refusal that doesn't read as a refusal (it just produces silence) — bypassing the framework's intended path of `find_capability` → use the right tool. The refusal-challenge intercept (#126) and the discovery-first prompt update (#153) both target this kind of evasion, but `no_response` slips past both because the agent technically *did* call a tool and the tool succeeded.

4. **Silent turn = bad UX.** From the user's perspective, "I sent a message; the spinner ran; nothing came back" is indistinguishable from a crash. Apps end up adding placeholder messages ("agent declined to respond") downstream to make the failure visible — at which point we've taxed the user with a real `respond` shape anyway. May as well require it up front.

The 651-char description it carries is dead weight on every turn's tool roster for the small fraction of cases where a no-reply is actually correct.

**Suggested fix:**

1. **Remove `NoResponseTool` from `CoreTools.all` and `coreToolNames`.** The class can stay in the package for apps that genuinely want to opt back in (multi-agent broker apps, voice-assistants where silence is valid), but it stops being a default.

2. **Update the framework instructions** (`Instructions.scala`'s discovery-first / response section) to drop any mention of `no_response`. The implicit contract becomes: every user turn produces some response — text via `respond`, a structured choice via `respond_options` / `respond_field`, or an honest `respond_failure` when the agent can't do what was asked.

3. **`respond_failure` covers the "can't help" case.** It already exists, already ends the turn, already produces a user-visible message. The agent picks it when the right answer is "I don't know" or "I can't do that." That's the legitimate replacement for `no_response`'s second use case ("no reply is appropriate" → "I can't do this here, here's why").

4. **Optional: surface a framework-level "silent turn" rule for the routing layer.** Apps that need a "this message isn't for this agent" code path should handle it before the orchestrator dispatches — e.g. `Sigil.shouldDispatch(participantId, message): Boolean` consulted before kicking the agent loop. Cheaper than running a turn just to call `no_response`.

**Severity:** API hygiene + tool-roster bloat. Removing it shrinks every default-roster turn by ~650 chars without losing real expressive power — the two cases `no_response` was supposed to cover are either structurally invalid (single-user apps) or better handled elsewhere (routing-layer filtering for multi-agent setups; `respond_failure` for "can't help"). The slight risk: existing apps that have been relying on it get a compile-break at the call site — but since it ships only in the system prompt + tool catalog (not directly invoked by app code), most apps just see a smaller tool roster post-upgrade.
