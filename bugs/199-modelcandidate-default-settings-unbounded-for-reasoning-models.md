# ❌ #199 — `ModelCandidate.settings` default `GenerationSettings()` lets reasoning-template local models hang the agent's forced-synthesis call

**Where:**
- `core/src/main/scala/sigil/provider/ModelCandidate.scala` —
  `settings: GenerationSettings = GenerationSettings()` field default.
- `core/src/main/scala/sigil/provider/GenerationSettings.scala:5-15`
  — defaults are `maxOutputTokens = None` (unbounded) and
  `reasoningMode = ReasoningMode.Auto`.
- `core/src/main/scala/sigil/Sigil.scala:5380-5400` — the cap-hit
  forced-synthesis recursion site; passes `forceResponseSynthesis = true`
  but **does not** override `GenerationSettings` for the recovery call.
  The recovery call inherits whatever the candidate's `settings` provide.
- `core/src/main/scala/sigil/Sigil.scala:5431-5442` — the no-triggers
  forced-synthesis recursion site; same omission.

**What's wrong:**

This is the same footgun as #196 (`ConsultTool.invoke` default
`GenerationSettings`), one layer up the stack. Where #196 affected
classifier consults specifically, this one affects every agent
inference call — including the framework's own forced-synthesis
recovery turn.

Apps configure their `ProviderStrategy` with a list of
`ModelCandidate`s. When the candidate is constructed without an
explicit `settings` argument, it gets `GenerationSettings()`:

- `maxOutputTokens = None` → unbounded output budget
- `reasoningMode = ReasoningMode.Auto` → reasoning-template models
  emit `reasoning_content` freely

For local llama.cpp serving a reasoning-template model
(qwen3.5-9b is the field example), this combination means:
the agent's per-iteration provider call can enter reasoning mode
and run unbounded until the model itself hits its context cap.

This is *especially* damaging on the **forced-synthesis recovery
call** because:

1. Forced-synthesis is supposed to be the framework's
   last-resort "make the model respond" turn — narrow
   `tool_choice` to the respond family, expect a short reply.
2. The model is *already* in trouble (the agent's normal iteration
   failed to call a tool — that's why forced-synthesis is running).
3. A 4-minute reasoning runaway here turns a recoverable hiccup
   into a permanently failed turn (the throw at #198 happens
   after this call completes or hits the context wall).

**Field evidence** — same Sage session as #198:

```
Forced-synthesis call to localhost:8081 (llama.cpp / qwen3.5-9b):
  prompt_n:        4,012 tokens
  predicted_n:    28,756 tokens of reasoning_content
  content chars:       0
  tool_calls chars:    0
  predicted_ms:  242,013 ms  (242 s)
  finish_reason: "length"  (hit model's 32K total context wall)
```

The request body:

```
model:            qwen3.5-9b-q4_k_m
max_tokens:       null         ← unbounded
reasoning_effort: null
tool_choice:      required     ← framework narrowed tools to the respond family
tools:            [cancel, respond_options, respond]
```

The framework correctly applied its part of forced-synthesis
(narrowed `tools`, set `tool_choice: required`). But because
`GenerationSettings` flowed through the candidate's bare default,
no `max_tokens` was on the wire and reasoning mode stayed on.
Qwen burned the entire 32K context budget on `reasoning_content`
without producing a single `tool_call` token. The hang then chained
into the misleading "(25)" runaway error (#198), and the cascade
republished the failure 3× to the chat (#200).

**Why this is structurally similar to but distinct from #196:**

#196 fixed the **consult** path by changing
`ConsultTool.invoke`'s default `generationSettings` to
`GenerationSettings.classifierDefault`. Classifier and
consult-style calls are categorically narrow-output —
`maxOutputTokens=1500, reasoningMode=Off` is the right safe
default.

`ModelCandidate.settings` covers the **agent inference** path,
which legitimately can want long-form generation (the user asked
for a paragraph, the model writes a paragraph). A blanket
`classifierDefault` here would be wrong — it would cap real replies
too aggressively and turn off reasoning where reasoning helps.

The narrower fix targets just the failure mode that bit us:
local-llama reasoning-template models on the forced-synthesis
recovery call.

**Suggested fix:**

Two scopes — call-site (minimum) and per-candidate (ideal).

**Minimum**: harden forced-synthesis itself. The forced-synthesis
recursion sites (5325-5335, 5380-5400, 5431-5442) already
override `tool_choice` to narrow the respond family. Have them
*also* override `GenerationSettings` for that one call, the same
way `Sigil.classifyTopicShift` overrides its consult call:

```scala
// At each forced-synthesis recursion site, thread the override
// through. Or — cleaner — have the call site that materializes
// the request from `ctx` apply a forced-synthesis-specific
// override when `ctx.forceResponseSynthesis` is true.

// In ConversationRequest construction (wherever per-call settings
// are resolved from `candidate.settings + ctx`):
val effectiveSettings =
  if (ctx.forceResponseSynthesis)
    candidate.settings.copy(
      // Cap aggressively — forced-synthesis is supposed to emit
      // ONE respond call, ≤ a few hundred tokens of content.
      maxOutputTokens = candidate.settings.maxOutputTokens.orElse(Some(2048)),
      // Reasoning mode must be Off on forced-synthesis. The model
      // is already in trouble (its normal turn failed); letting
      // it think freely turns a recoverable hiccup into a 4-minute
      // dead-end. The narrow tool_choice means there's nothing
      // worth reasoning about anyway.
      reasoningMode = ReasoningMode.Off
    )
  else
    candidate.settings
```

This is surgical: only forced-synthesis is affected, normal turns
keep whatever the app configured.

**Ideal**: provide a `ModelCandidate.localCheapDefault` (or similar
named helper) so apps that wire up a local-llama candidate don't
have to know to set reasoning-off + a token cap. Same shape as
`GenerationSettings.classifierDefault` from #196, but tuned for
agent-inference use:

```scala
// core/src/main/scala/sigil/provider/ModelCandidate.scala
object ModelCandidate:
  /** Sensible defaults for a local-llama candidate serving a
    * reasoning-template model. Disables reasoning (the chain-of-
    * thought channel inflates latency without improving small-task
    * tool selection) and caps `maxOutputTokens` at 4096 (generous
    * for real replies, hard wall against reasoning runaway). Apps
    * can still construct `ModelCandidate(... settings = ...)` with
    * a custom config for non-reasoning-template models. */
  val localReasoningTemplateDefaults: GenerationSettings = GenerationSettings(
    maxOutputTokens = Some(4096),
    reasoningMode   = ReasoningMode.Off
  )
```

Apps then write:

```scala
val llamaC = ModelCandidate(
  modelId             = llamaId,
  settings            = ModelCandidate.localReasoningTemplateDefaults,
  supportedComplexity = Set(Complexity.Low)
)
```

…instead of discovering the failure mode in production.

Apply both. The call-site fix protects against the case where the
app didn't (or couldn't) tune the candidate; the helper makes the
right tuning discoverable for the next consumer.

**Related:**
- #196 — the same lesson, applied to `ConsultTool.invoke`'s default.
- #198 — the misleading error message that fires after this hang.
- #200 — the failure-message cascade that multiplies the user-visible damage.
