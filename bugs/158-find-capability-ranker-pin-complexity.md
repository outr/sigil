# ❌ #158 — `find_capability` doesn't rank `pin_complexity` above `change_mode` for tier-routing intents

**Where:**
- `core/src/main/scala/sigil/tool/DbToolFinder.scala` — BM25 ranker over `Tool.searchText`
- `core/src/main/scala/sigil/tool/core/ChangeModeTool.scala:18` — no `keywords` field; description contains "Switch operating mode", "user's task", "change_mode"
- `core/src/main/scala/sigil/tool/provider/PinComplexityTool.scala:55` — keywords: `pin, lock, force, stick, fix, always, deterministic, complexity, tier, routing, cost, ceiling, level`

**What's wrong:**

In a Sage wire log, the agent ran `find_capability("complexity pin adjust medium level")` and followed up with `change_mode("coding")` — completely wrong. The user's intent was "pin to medium complexity tier" and the right tool is `pin_complexity`. The ranker either (a) failed to return `pin_complexity` in the top results, or (b) returned it but `change_mode` outranked it because of `change_mode`'s description prelude ("Call BEFORE find_capability when a listed mode clearly matches the user's task") priming the agent to defer to it.

Either way, the user's intent doesn't reach the right tool. The agent then enters coding mode, realizes coding-mode tools don't include `pin_complexity`, reverts to conversation mode, calls find_capability again **with the same keywords** — looping. Five turns later, no progress (covered separately as #159).

Tokens in the query: `complexity`, `pin`, `adjust`, `medium`, `level`. PinComplexityTool's keywords cover four of five (complexity, pin, level, tier ≈ medium). ChangeModeTool has no keywords field and zero of five in its description. By BM25, `pin_complexity` should crush `change_mode` for this query — but the agent picked `change_mode`. Two possible failure paths:

1. **Ranker excluded `pin_complexity`**: it isn't in the persisted `db.tools` row for this conversation's chain. Possible if the polymorphic registration or static-tool listing skipped it.
2. **Ranker returned it lower than `change_mode`**: the ranker is matching `change_mode` on something unexpected (its description's "user's task" phrase coincidentally tokenizes against the query? unlikely but worth checking with a debug log of the ranker's per-tool BM25 scores).

**Reproducer (Sigil-side spec sketch):**

```scala
class FindCapabilityRankingSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "find_capability ranker" should {
    "rank pin_complexity above change_mode for tier-pinning intent" in {
      val req = DiscoveryRequest(keywords = "complexity pin adjust medium level", ...)
      TestSigil.findTools(req).map { hits =>
        val names = hits.map(_.schema.name.value)
        val pinIdx    = names.indexOf("pin_complexity")
        val changeIdx = names.indexOf("change_mode")
        pinIdx should be >= 0 withClue s"pin_complexity should appear in results: $names"
        if (changeIdx >= 0) {
          pinIdx should be < changeIdx withClue
            s"pin_complexity must rank above change_mode for this query, got $names"
        }
      }
    }
  }
}
```

**Suggested fix:**

Two complementary changes:

1. **Add a `keywords` field to `ChangeModeTool`** that's tight on its actual semantics — `mode, switch, change, operating, posture, kit, toolset` — and DOES NOT include words like `pin`, `tier`, `level`, `complexity`, `cost`. The current empty-keywords default lets the ranker grab description matches that are accidentally tier-shaped.

2. **Boost weight on `keywords` vs `description` in the indexed `searchText`.** Today the index is name + description + keywords concatenated. Description text is long; tools with no keywords field rely entirely on description matches, which produces accidental scoring on incidental words. Either:
   - Pre-weight `keywords` 5×–10× by repeating tokens in the index
   - Or use a multi-field Lucene query with explicit boosts

This makes the discovery-first promise more honest: a tool's `keywords` declares its intent surface; the ranker should respect that more than a paragraph of prose.

**Severity:** Functional — the agent calls the wrong tool, loops, never accomplishes the user's request. The user's reported test case ("Switch to medium complexity") is a one-line action that takes 5+ agent iterations and zero successful tool calls today.
