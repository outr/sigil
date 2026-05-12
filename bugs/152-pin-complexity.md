# ❌ #152 — No way to pin a conversation's `Complexity` tier; routing always falls through to `inferComplexity`

**Where:**
- `core/src/main/scala/sigil/conversation/Conversation.scala` — has `pinnedModelId: Option[Id[Model]]`, no `pinnedComplexity`
- `core/src/main/scala/sigil/provider/strategy/RoutedStrategy.scala` (or wherever `ProviderStrategy.routed` resolves) — calls `inferComplexity` per message
- `core/src/main/scala/sigil/tool/provider/PinModelTool.scala` / `UnpinModelTool.scala` — the existing pin primitive that this should mirror

**What's wrong:**

The framework's complexity-routing strategy classifies every user message via `inferComplexity` to pick which `ModelCandidate.supportedComplexity` tier handles the turn. That's the right default — the classifier should choose. But there's no escape hatch when the user wants to override the choice.

Use cases the missing primitive blocks:

1. **"Use medium complexity for this conversation"** — the user has read enough wrong-tier classifications and wants to pin. Today they can `pin_model("digitalocean/kimi-k2.5")` but that locks them to a *specific model*, not a *tier*. If kimi-k2.5 is unavailable for one turn (rate limit, cooldown, transient failure), pin_model can't degrade gracefully; pin-complexity would let the routing chain pick the next-best model that still supports Medium.

2. **Cost ceiling enforcement** — "stay under Medium for everything in this conversation" caps spend without naming a model. The chain handles which provider implements that tier.

3. **Routing diagnosis** — temporarily forcing a tier to test "does this model handle X cleanly?" without disturbing the per-message classifier or affecting other conversations.

4. **User trust override** — the classifier sometimes mis-routes (Sage observed several VeryHigh classifications for routine code-reading questions earlier today). The user should be able to say "stay at Medium" and have the framework honor it.

The symmetric API exists for models (`pinnedModelId` + the two tools). The complexity case is identical in shape; just no plumbing.

**Suggested fix:**

Mirror the `pinnedModelId` machinery for `Complexity`:

1. **`Conversation.pinnedComplexity: Option[Complexity]`** — new field, defaults `None`.

2. **Routing resolution checks pin first.** In `ProviderStrategy.routed`'s candidate-selection path:
   ```scala
   val effective = conversation.pinnedComplexity.orElse(
     inferComplexity.flatMap(_(text, ctx).attempt.toOption.flatten)
   )
   ```
   Pin wins over inference; inference wins over the strategy's default.

3. **`PinComplexityTool` + `UnpinComplexityTool`** — mirror the model tools:
   ```scala
   case object PinComplexityTool extends TypedTool[PinComplexityInput](
     name = ToolName("pin_complexity"),
     description = """Pin this conversation's routing complexity tier (Low / Medium / High / VeryHigh).
                     |All subsequent turns route to the matching candidate regardless of the classifier.
                     |Use `unpin_complexity` to revert to classifier-driven routing.""".stripMargin,
     keywords = Set("pin", "lock", "complexity", "tier", "force", "routing"),
     ...
   ) { ... }

   case object UnpinComplexityTool extends TypedTool[Unit](
     name = ToolName("unpin_complexity"),
     description = """Revert this conversation to classifier-driven complexity routing.""".stripMargin,
     keywords = Set("unpin", "unlock", "auto", "default", "complexity")
   )
   ```

4. **`switch_model` extends to recognize `"low" / "medium" / "high" / "very high"`** as complexity tier names, dispatching to `pin_complexity` instead of model resolution. So a user typing "switch to medium complexity" gets routed via the existing switch_model flow with no extra discovery dance.

5. **Per-message reasoning** — the framework's existing `RouteResolved` notice (if it carries the chosen complexity) should reflect the pin source ("pinned" vs "classified") so the UI / wire log can show *why* a tier was picked.

**Severity:** Feature gap. The complexity routing primitive exists but isn't user-overridable. Apps can work around it (Sage could override `inferComplexity` to consult its own per-conversation flag) but that's pointless when the framework's `Conversation` already has a slot for exactly this shape of override.
