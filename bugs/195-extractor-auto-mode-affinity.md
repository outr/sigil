# ❌ #195b — `ExtractMemoriesTool` should learn to stamp `modeAffinity` on extracted memories

**Where:**
- `core/src/main/scala/sigil/tool/consult/ExtractMemoriesInput.scala`
  — `ExtractedMemory` case class; needs an optional
  `modeAffinity: List[String]` field.
- `core/src/main/scala/sigil/tool/consult/ExtractMemoriesTool.scala`
  — description; needs guidance teaching the model when to populate
  `modeAffinity`.
- `core/src/main/scala/sigil/conversation/compression/extract/StandardMemoryExtractor.scala`
  — persistence; needs to resolve the model-supplied mode names
  against `sigil.availableModes` and stamp `ContextMemory.modeAffinity`.

**What's pending:**

Sigil bug #195 shipped the storage + retriever + `SaveMemoryTool`
half of mode-scoped memories — apps that explicitly call
`save_memory(modeAffinity = Set("coding"))` get the gated
retrieval. The per-turn memory extractor still emits universal
memories regardless of mode-scope phrasing in the user's message.

The extractor change was attempted in the original #195 PR but
backed out because: adding the field to `ExtractedMemory`'s schema
consistently drove the smaller test model (qwen3.5-9b via local
llama.cpp) to drop `key` emission across the entire output. Three
runs in a row, deterministic — every extracted memory had
`key = None`. The new optional field stole enough attention from
the "ALWAYS supply a key" instruction that the model produced
skeletal records.

Suspected cause: small models become more conservative about
filling optional fields when more of them exist. Strict-mode
schema generation in particular surfaces every optional field as
a `null` placeholder, and the model "answers" by leaving optional
fields null — including `key`, which is the one we care most about.

### Path forward (suggested)

Three options, in order of how surgical they are:

**A — Bigger default extractor model.** Run `LlamaCppPerTurnExtractionSpec`
against a larger model (qwen3-30b, gpt-5-mini, claude-haiku) and
confirm key emission survives the extra field. If yes, the field
goes in as-is + the existing tests' model recommendation gets
bumped.

**B — Conditional schema.** Gate the `modeAffinity` field on
the `ExtractedMemory` schema behind a Sigil hook
(`extractorModeAffinityEnabled: Boolean = false`). Apps with
beefier extractor models opt in; apps using small local models
keep the existing leaner schema. Mechanism in place but the
extractor can't auto-detect mode-scope phrasing until apps opt in.

**C — Separate tool variant.** Define `ExtractMemoriesWithModeTool`
alongside the existing one. The `StandardMemoryExtractor` picks
which to invoke based on a Sigil hook. Apps using small models
keep the simple tool; apps with capable models use the mode-aware
variant. More code surface but cleaner per-app gating.

Best path is probably A — confirm the failure is qwen3.5-9b-specific
on a larger model. If it isn't, B (gated hook) is the simplest
ship.

### Test that's currently failing if you re-add the field

`spec.LlamaCppPerTurnExtractionSpec`:
> "should persist a keyed memory from a high-signal user turn against a real LLM"
>
> Expected: extractor returns 4 entries with at least one key set.
> Observed (with modeAffinity field on schema): 4 entries, all
> `key = None`.

Same shape in `spec.LlamaCppInitializeMemoriesSpec` for the
initial-memory seeding path that also goes through the extractor.

### Related

- Bug #195 — the shipped half (storage / retriever / `SaveMemoryTool`).
  Reads / writes via the explicit tool already work; this bug
  closes the auto-extraction loop so user phrasing like
  "always create failing tests when coding" gets mode-scoped
  without the agent having to remember to pass `modeAffinity`.
- Per memory `feedback_dont_widen_api_for_tests.md` — small models
  shouldn't force the schema surface. A's "use a bigger model" or
  B's gated hook respects that.
