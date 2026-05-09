# ❌ #91 — Cost projection silently 0 because `cache.find(modelId)` mismatches bare vs prefixed ids

**Where:**
`core/src/main/scala/sigil/Sigil.scala:3378-3402`
(`applyMessageCostToConversation` → `cache.find(mid)`).

**What's wrong:** `applyMessageCostToConversation` looks up
pricing via `cache.find(m.modelId)` where `m.modelId` is
whatever the message stamp recorded. Apps like Sage register
candidates with **bare** ids (`Model.id("gpt-5.5")`,
`Model.id("claude-opus-4-7")`). `OpenRouter.refreshModels`
populates the cache with **prefixed** ids
(`openai/gpt-5.5`, `anthropic/claude-opus-4-7`). The two never
match, `cache.find` returns `None`, the cost increment is `0`,
and `ConversationCostUpdated` never fires.

Net effect: cost badge stays `<$0.01` forever even after
significant gpt-5.5 + Claude traffic. Live trace from Sage
today shows messages stamped with `modelId=gpt-5.5` and
`modelId=claude-opus-4-7`, neither resolvable in the registry.

The provider-resolution path (`Sage.providerFor`) was migrated
to prefix-based string matching for exactly this reason (per
its docstring) — but the cost path stayed cache-based and
inherits the same id-mismatch failure mode.

**Suggested fix:** Normalise modelIds at the cost-projection
lookup. Two complementary angles:

### 1. Provider-prefix resolver in the cache

Add a `cache.findWithProvider(bareId, providerHint)` (or have
`cache.find` itself try the bare id, then `<defaultProvider>/<id>`,
then walk the registry for a unique suffix match):

```scala
def find(modelId: Id[Model]): Option[Model] = {
  val direct = ref.get.get(modelId)
  if (direct.isDefined) return direct
  // Fallback: walk for "<provider>/<modelId>" entries that suffix-match.
  val raw = modelId.value
  ref.get.values.find(m => m._id.value == raw || m._id.value.endsWith(s"/$raw"))
}
```

Cheap (one map lookup + at-most-one walk on miss). Preserves
exact-match performance; only the miss path pays the walk.

### 2. Apps stamp prefixed ids on Messages

Sage (and any app using non-llama providers) updates its
candidate construction to use prefixed ids:

```scala
ModelCandidate(Model.id("openai/gpt-5.5"), GenerationSettings())
ModelCandidate(Model.id("anthropic/claude-opus-4-7"), GenerationSettings())
```

…and adjusts `providerFor`'s prefix dispatch to match
`"openai/"` / `"anthropic/"` instead of `"gpt-"` / `"claude-"`.

(1) is the framework-level fix; (2) is the app-side discipline.
(1) makes existing apps work without changes; (2) is cleaner
long-term but requires every app to migrate.

The right answer is (1) in the framework — apps shouldn't need
to know about OpenRouter's id format to get cost tracking. The
cache should be tolerant of bare ids when there's an
unambiguous prefixed match.
