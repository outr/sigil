# ❌ #159 — Agent re-issues identical `find_capability` queries after a failed attempt, looping until `maxAgentIterations`

**Where:**
- `core/src/main/scala/sigil/orchestrator/Orchestrator.scala` — agent loop dispatch
- `core/src/main/scala/sigil/tool/core/FindCapabilityTool.scala` — receives the request
- `core/src/main/scala/sigil/conversation/compression/ParaphraseLoopDetector.scala` — existing loop detection (doesn't cover this shape)

**What's wrong:**

In a Sage wire log, the agent followed this exact pattern within one user turn:

```
L55/56   find_capability("complexity pin adjust medium level")
L73/74   change_mode("coding")                      ← picked wrong tool from results
L93/94   record_consent("start_coding", approved)   ← fabricated tool name (separate bug #160)
L109/110 change_mode("conversation", reason=null)   ← reverted
L125/126 find_capability("complexity pin adjust medium level")  ← SAME EXACT QUERY
```

After realizing change_mode → coding was wrong, the agent reverted to conversation mode and ran the same find_capability with the same keywords. The keyword ranker is deterministic, so it returned the same results, so the agent was about to pick `change_mode` again and loop forever — only stopped because the user gave up.

The framework's `ParaphraseLoopDetector` exists but it watches for paraphrased CONTENT (the agent saying the same thing different ways without acting). It doesn't catch this case: the agent acted (called tools), the actions just don't produce progress. The detector needs an analog for query repetition.

**Reproducer (Sigil-side spec sketch):**

```scala
class AgentRepeatedQueryLoopSpec extends AsyncWordSpec with AsyncTaskSpec with Matchers {
  TestSigil.initFor(getClass.getSimpleName)

  "the agent loop" should {
    "intervene when find_capability is called twice with identical keywords " +
    "without an intervening dispatch of a non-find_capability tool that wasn't already tried" in {
      // Drive a synthetic conversation:
      //   1. User: "Switch to medium complexity"
      //   2. Agent: find_capability("...keywords...")
      //   3. Agent: change_mode("coding")
      //   4. Agent: change_mode("conversation")
      //   5. Agent: find_capability("...same keywords...")   ← framework should intercept
      // The framework should inject a guidance Tool-role Failure telling
      // the agent to refine its query or pick a different result from
      // the previous find_capability's hits.
      ...
    }
  }
}
```

**Suggested fix:**

Treat repeated identical find_capability queries within one user turn as a self-loop signal. The framework already tracks per-turn tool-call history (used for the refusal-challenge intercept, #126). Add to the loop detector:

```scala
// In ParaphraseLoopDetector or a new RepeatedQueryDetector:
def detect(toolHistory: List[ToolInvocation], ...): Option[LoopSignal] = {
  val findCalls = toolHistory.filter(_.toolName == "find_capability")
  val byKeywords = findCalls.groupBy(_.input.asInstanceOf[FindCapabilityInput].keywords.trim.toLowerCase)
  byKeywords.find { case (_, calls) => calls.size >= 2 }.map { case (keywords, _) =>
    LoopSignal.RepeatedQuery(keywords)
  }
}
```

When detected, the framework injects a Tool-role Failure paired to the agent's invoking ToolInvoke with text along the lines of:

> The previous `find_capability("$keywords")` returned the same results as this one will. Either pick a different result from the previous call's hits, or refine the query with different keywords. Repeating the same search will not produce a different answer.

The agent re-runs, sees the guidance, and adjusts. Loop safety: only fire once per user turn (same shape as #126's refusal-challenge guard).

**Why this isn't a prompt-only fix:**

The discovery-first prompt already mentions discovery, but the agent's reasoning trace doesn't include "did I already ask this?" comparing previous tool calls to the next. The framework knows the call history; the agent only sees what's in its context window. A detection layer here is the cheapest path to "see your own repetition." A prompt rule like "never call find_capability twice with identical keywords in one turn" might help but won't catch near-identical queries.

**Severity:** High. Any case where the keyword ranker returns the wrong top tool (genuine bug per #158, OR ambiguous query) puts the agent in a 5-10 iteration loop, hits `maxAgentIterations`, raises `AgentRunawayException`, leaves the user staring at "(agent completed without a reply)" after a minute of wasted compute. The current architecture has no way out except `maxAgentIterations` killing the turn.
