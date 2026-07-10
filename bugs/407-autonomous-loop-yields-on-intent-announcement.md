# ⚠️ #407 — Autonomous loop yields the turn on an intent-announcement, with no continuation safety-net

**Where:** Sigil autonomous agent loop (turn-end / `stop_reason` handling). Downstream consumer code working around it: `shopmagic/backend/.../ShopMagicServer.scala` (guidelines) and `shopmagic/backend/.../ShopMagicRouter.scala:119` (`looksLikeStalledContinuation`).

**What's wrong:** In autonomous mode the model frequently ends a turn by *narrating the next step* — "Next I'll build the rendering layer… I'll preview it and bring it back for your review" — and then stops (`stop_reason = end_turn`, no tool call). The framework treats that as a normal turn boundary and waits for the user. The user then has to type "keep going", after which the agent reliably continues and completes the work. So the work isn't blocked on anything real — the loop just yielded on an announcement of its own next step.

A downstream tester reported this repeatedly and consistently:

> "ShopMagic will explain all the things it's going to do, then do part of the task and end with saying it's about to do the next thing only to not do it. But if I tell it to keep going, it will reliably do the work. So there is a workaround, but it's annoying."

There is no framework-side notion of "the agent announced unfinished work but yielded", and no continuation / "again" hook a consumer can use short of observing settled messages (`SettledEffect`) and re-injecting a synthetic user turn — which then surfaces a fake "continue" message in the transcript. The only existing lever downstream (ShopMagicRouter) is *reactive*: it detects complaint phrases ("try again", "you didn't", "keep going") on the NEXT user turn and escalates `Complexity.High`. That helps routing after the fact but does nothing to prevent the yield.

**Workaround in place:** ShopMagic prompt hardening — a guideline plus a Vibe-mode skill line explicitly forbidding "announce a next step, then stop" and instructing the agent to take the announced step in the same turn. This reduces frequency but can't guarantee it (especially on weaker tiers); the loop itself will still yield if the model emits `end_turn`.

**Suggested fix:** One of:
- (a) In the autonomous loop, when the model yields with `end_turn` but its final message contains a forward-looking self-commitment ("I'll…", "next I'll…", "now I'm going to…") and no tool call and no user-directed question (`respond_options` / an explicit ask), auto-continue one iteration. Bound it (a small max-consecutive-auto-continues per user turn) so a genuinely stuck model can't loop.
- (b) Expose an `onTurnYield` / `shouldContinue(finalMessage): Boolean` hook so a consumer can decide to continue *without* re-injecting a fake user message (the continuation should read as the same turn, not a new user turn).

Today every consumer has to reinvent (a) via `SettledEffect` + synthetic-user-message injection, which pollutes the transcript with a fake "continue".
