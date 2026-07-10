# ❌ #408 — Conversation `cumulativeCost` diverges ~6× from the sum of per-turn `TurnCost`

**Where:** `core/src/main/scala/sigil/Sigil.scala` (publishes `ConversationCostUpdated` from the conversation's running `cumulativeCost`), `core/src/main/scala/sigil/TurnCost.scala`, `core/src/main/scala/sigil/signal/ConversationCostUpdated.scala`.

**What's wrong:** For one conversation, the cumulative cost Sigil publishes via `ConversationCostUpdated.cost` is far larger than the sum of the per-turn `TurnCost.cost` values the same turns emit. A downstream consumer (ShopMagic) ledgers every `onTurnCost(TurnCost)` into a per-turn cost table (`CostEntry`); summing that table for a conversation yields ~**$4.76**, while `ConversationCostUpdated` for the *same* conversation reports ~**$29.95** — roughly a 6× gap. Both are supposed to derive from the same per-provider-call costs, so they should reconcile.

A downstream tester flagged the large number as implausible for the work done:

> "this was the estimated cost of just telling me that last message … I have a hunch the usage calculation is just wrong." (`$29.95` shown for a turn reporting *0 seconds, 0 actions*.)

The `$29.95` is also suspiciously stable across turns and suspiciously round, which is what prompted filing this rather than assuming it's just "cumulative vs per-turn."

**Candidate causes:**
- `cumulativeCost` counts provider calls that never surface as a `TurnCost` (routing/classification consults, summarization, sub-agent calls). If so the two are measuring different things — that's defensible but should be *documented and itemized* so a consumer can show a trustworthy figure.
- `cumulativeCost` double-counts, or applies a wrong price multiplier / stale price table → genuinely inflated.

Either way, a consumer can't surface a trustworthy spend number today because the framework's two cost surfaces disagree by ~6×.

**Impact on consumer:** ShopMagic's cost UI reads three sources that now disagree — the per-turn card reads Sigil's `ConversationCostUpdated` cumulative ($29.95), the breakdown popover reads ShopMagic's own ledger ($4.76). Downstream mitigation will consolidate the UI onto the ledger, but that only hides the divergence; it doesn't reconcile it.

**Suggested fix:** Make `sum(TurnCost.cost for a conversation) == ConversationCostUpdated.cost` for that conversation, OR — if `cumulativeCost` intentionally includes non-`TurnCost` provider spend — expose that split (charged-turn cost vs auxiliary/routing spend) so consumers can render a defensible per-turn and per-conversation number. Also worth confirming the Opus/model price table the cumulative path uses matches the per-`TurnCost` path.
