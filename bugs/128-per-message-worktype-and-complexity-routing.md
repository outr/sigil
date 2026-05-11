# ❌ #128 — Per-message WorkType + Complexity inference in `ProviderStrategy.routed`; today's routing is mode-locked

**Where:**
- `core/src/main/scala/sigil/provider/ProviderStrategy.scala` — `routed(default, routes: Map[WorkType, List[ModelCandidate]])` factory.
- `core/src/main/scala/sigil/provider/ModelCandidate.scala` — current shape: `(modelId, generationSettings)`.
- `core/src/main/scala/sigil/Sigil.scala:1374-1389` — per-turn strategy resolution: `conversation.pinnedModelId` → `mode.strategyId` → `space.resolveProviderStrategy`. None of these read the user's latest message; routing is locked to `mode.workType` which itself is set at mode-change time.
- `core/src/main/scala/sigil/provider/Mode.scala:45` — `workType: Option[WorkType]` — per-mode, not per-message.

**What's wrong:**

Model selection today is one-dimensional and mode-driven. `Mode.workType` → `WorkType` → `ProviderStrategy.routed.routes(workType)` → first available candidate. The agent gets the same model for every user message within a mode, regardless of whether the user typed "hi" or "evaluate the security of this auth flow." Two consequences:

1. **No per-message complexity routing.** A user asking a complex question in `ConversationMode` (default for chat apps) is routed to the cheap-first chain — local llama answers a question that needs frontier reasoning, badly. The opposite case (user asks "what time is it" in `CodingMode`) wastes frontier tokens on trivia.

2. **Model switching requires mode switching.** The only way to change the model mid-conversation is `change_mode` (coarse — changes persona, instructions, tool roster too) or `pin_model` (coarse — pins for the rest of the conversation). Neither matches the "local-as-orchestrator, frontier-when-needed" pattern Sage's user wants.

Wire-log evidence (`/home/mhicks/projects/clients/outr/sage/backend/target/sage-wire.jsonl`, 2026-05-11): the user typed `"switch to gpt-5.5"` after a Qwen-handled run thrashed on a security evaluation. Even pin_model existing in `staticTools` wouldn't have helped on its own — the right answer is that the security evaluation should have routed to gpt-5.5 from the first turn, automatically, based on the message content.

**The two missing axes:**

- **WorkType inference per user message**: the message text already carries enough signal to classify (CodingWork vs ConversationWork vs AnalysisWork). Letting `mode.workType` lock this misses the per-message context.
- **Complexity dimension**: orthogonal to WorkType. Low / Medium / High. The same WorkType can range from trivial to architectural and should route to different models. A model isn't "good at coding" — it's "good at coding up to a certain complexity ceiling."

**Test first:**

```scala
class PerMessageRoutingSpec extends AsyncWordSpec with Matchers {
  "ProviderStrategy.routed with inferWorkType + inferComplexity" should {
    "route based on the user's latest message, not the mode's workType" in {
      // Conversation mode (workType=ConversationWork). User types
      // "evaluate the security of this OAuth flow." Classifier
      // returns (AnalysisWork, High). Assert the resolved
      // provider matches the AnalysisWork+High candidate chain,
      // not the ConversationWork chain.
    }

    "skip candidates whose supportedComplexity doesn't include the requested level" in {
      // Chain: [llama (supports {Low}), gpt-5.5 (supports {Low, Medium, High}), claude (all)].
      // Request: complexity = High. Assert resolved candidate is gpt-5.5
      // (llama skipped because High not in its set; gpt is cheaper than claude).
    }

    "default to Medium complexity when inferComplexity is None" in {
      // Strategy declared without inferComplexity. Assert routing key
      // uses Complexity.Medium for every turn.
    }

    "cache classifier result per user message across agent iterations" in {
      // User types one message. Agent runs 5 iterations. Assert
      // inferWorkType and inferComplexity each fire EXACTLY once,
      // not 5 times. The cached (WT, C) drives all iterations.
    }
  }

  "request_escalation tool" should {
    "elevate complexity for subsequent iterations of the same user turn" in {
      // Classifier picks Medium. Agent calls request_escalation(reason).
      // Assert next iteration's resolved provider is the High-tier
      // candidate (one tier up).
    }

    "compose with cap-hit (#125): forced synthesis auto-bumps one tier" in {
      // Agent hits maxAgentIterations on Medium complexity. Assert the
      // forced-synthesis turn fires against the High-tier model, so the
      // synthesis itself has the strongest available reasoning.
    }
  }

  "classifier skip gates" should {
    "not invoke inferWorkType / inferComplexity when conversation.pinnedModelId is set" in {
      // Set both classifiers to fakes that throw if called. Pin a model
      // on the conversation. Drive a turn. Assert no fake fires (the
      // pinned-model short-circuit at Sigil.scala:1375 runs first).
    }

    "not invoke inferWorkType when every WorkType chain is identical" in {
      // Strategy.routed with routes = Map(All workTypes -> sameChain).
      // inferWorkType fake throws if called. Assert no throw — the
      // strategy's workTypeMatters = false short-circuits.
    }

    "not invoke inferComplexity when the resolved chain has no tier variation" in {
      // Strategy where ConversationWork chain = [llama-all-tiers,
      // claude-all-tiers]. inferComplexity fake throws if called.
      // User message classifies as ConversationWork. Assert no throw
      // because the resolved chain has no complexity differentiation.
    }

    "DO invoke inferComplexity when the resolved chain has tier variation" in {
      // Same strategy but CodingWork chain = [llama-low-only,
      // gpt-all-tiers]. User message classifies as CodingWork. Assert
      // inferComplexity fires because the chain differentiates.
    }
  }
}
```

All specs must fail on current `main`.

**Suggested fix:**

### 1. New `Complexity` enum

```scala
package sigil.provider

enum Complexity derives RW:
  case Low      // single fact, simple syntax, one-line answer
  case Medium   // multi-step reasoning, focused task, ~5 reasoning steps
  case High     // architectural, cross-file, deep reasoning, security analysis
```

### 2. `ModelCandidate` carries a complexity filter

```scala
case class ModelCandidate(
  modelId: Id[Model],
  generationSettings: GenerationSettings = GenerationSettings(),
  supportedComplexity: Set[Complexity] = Set(Complexity.Low, Complexity.Medium, Complexity.High)
)
```

Default of "all three" preserves backwards-compat — existing apps see no behavior change. Apps that want gating declare per-candidate which complexity tiers each model handles. Sage's local llama would declare `Set(Low)`; gpt-5.5 declares `Set(Low, Medium, High)`; claude-opus declares the same. **The strategy walks the chain in order and skips candidates whose `supportedComplexity` doesn't include the requested level** — natural fallthrough to the next-tier model with no separate routes-per-complexity table.

### 3. `ProviderStrategy.routed` gains optional inference callbacks

```scala
def routed(
  default: List[ModelCandidate],
  routes: Map[WorkType, List[ModelCandidate]],
  inferWorkType: Option[InferWorkType] = None,
  inferComplexity: Option[InferComplexity] = None
): ProviderStrategy
```

Where:

```scala
type InferWorkType   = (userMessage: String, context: TurnContext) => Task[WorkType]
type InferComplexity = (userMessage: String, context: TurnContext) => Task[Complexity]
```

Apps wire these to a cheap consult call (typically the local model classifying via structured output). Recommended single combined call — one round-trip, both signals returned in a typed JSON envelope. Default behavior when callbacks are None: `mode.workType` as before, `Complexity.Medium` as the default level.

### 4. Per-turn resolution flow

```scala
// In Sigil.scala's runAgentTurn:
val routingKey: Task[(WorkType, Complexity)] = userTurnCache.get(userMessageId) match {
  case Some(cached) => Task.pure(cached)
  case None =>
    for
      wt <- if (strategy.shouldClassifyWorkType)
              strategy.inferWorkType.get(userMsg, ctx)
            else
              Task.pure(mode.workType)
      cx <- if (strategy.shouldClassifyComplexity(wt))
              strategy.inferComplexity.get(userMsg, ctx)
            else
              Task.pure(Complexity.Medium)
      _  <- userTurnCache.put(userMessageId, (wt, cx))
    yield (wt, cx)
}

val candidate: Task[ModelCandidate] = routingKey.flatMap { (wt, cx) =>
  val chain = strategy.routes.getOrElse(wt, strategy.default)
  Task.pure(chain.find(c => c.supportedComplexity.contains(cx)).getOrElse(chain.head))
}
```

Cache is keyed on user-message-id, scoped to that user turn. Multiple agent iterations within the same user turn share the classification — one classifier call total. Cache cleared when a new user message arrives.

### 4a. Classifier-skip conditions (opt-in by default)

Classification is wasted work when the outcome can't change. Three independent skip gates, evaluated cheapest-first:

1. **Pinned model** — `conversation.pinnedModelId` short-circuits the strategy entirely (existing behavior at `Sigil.scala:1375-1380`). The classifier never runs because the strategy itself is never consulted. No new code needed; this gate already works.

2. **Explicit opt-out** — `inferWorkType = None` and/or `inferComplexity = None`. Apps that don't want per-message routing pass nothing; the strategy uses `mode.workType` + `Complexity.Medium` as today. This is the *default*: classification is opt-in via the callbacks, not opt-out.

3. **No-effect detection** (static inspection at strategy construction):

   ```scala
   case class RoutedStrategy(...) extends ProviderStrategy:
     // True when at least one WorkType's chain differs from another's
     // (or from default). When every chain is identical, classifying
     // workType can't change the candidate list.
     private val workTypeMatters: Boolean = {
       val allChains = routes.values.toSet + default
       allChains.size > 1
     }

     // True when at least one candidate in any reachable chain has a
     // supportedComplexity set that differs from another's. When every
     // candidate supports the same tiers, complexity classification
     // can't filter anything.
     private val complexityMatters: Boolean = {
       val allCandidates = (default :: routes.values.toList).flatten
       allCandidates.map(_.supportedComplexity).toSet.size > 1
     }

     def shouldClassifyWorkType: Boolean =
       inferWorkType.isDefined && workTypeMatters

     def shouldClassifyComplexity(wt: WorkType): Boolean =
       inferComplexity.isDefined && {
         // Per-chain check: classifying complexity only matters if the
         // chain we're about to walk has candidates with differing
         // supportedComplexity sets. If the resolved WorkType points
         // at a chain whose candidates are all-tiers, complexity is
         // moot for THIS turn even if it matters for other WorkTypes.
         val chain = routes.getOrElse(wt, default)
         chain.map(_.supportedComplexity).toSet.size > 1
       }
   ```

   Both checks are O(1) per turn — `workTypeMatters` and `complexityMatters` precompute at construction. `shouldClassifyComplexity` does the per-chain check at turn time but it's a cheap iteration over a small list.

The result: classifier round-trips only happen when their outcome can actually shape the resolved candidate. For Sage's recommended config (llama with `Set(Low)`, gpt/claude with all-tiers, per-WorkType chains that differ), both classifications fire. For a single-model app, neither fires regardless of whether callbacks are wired. For an app where every WorkType routes to the same all-tiers chain, the wiring is inert and free.

### 5. Agent escalation: `request_escalation` tool

```scala
case object RequestEscalationTool extends TypedTool[RequestEscalationInput](
  name = ToolName("request_escalation"),
  description =
    """Escalate this turn's complexity tier so subsequent iterations
      |route to a more capable model. Use when you realize the task is
      |harder than the classifier's initial assessment — e.g. a question
      |that looked simple has cross-file implications, or your initial
      |reasoning kept hitting dead ends.
      |
      |Effect: bumps the cached (workType, complexity) for the current
      |user turn one tier up (Low → Medium → High). Your NEXT iteration
      |runs against whichever model in the chain supports the elevated
      |tier. The current iteration's response (this tool call) still
      |runs on the original model.
      |
      |Don't escalate just because a tool returned empty results — that's
      |gathering progress, not complexity. Escalate when the SHAPE of the
      |answer needs more reasoning than you have headroom for.""".stripMargin,
  ...
)
```

`RequestEscalationInput(reason: String)` — the reason is stored on the conversation for transparency and shows up in the next checkpoint. Three escalation calls from `Low` clamp at `High`; further calls become no-ops with a "(already at max tier)" diagnostic.

### 6. Cap-hit (#125) composition

When `AgentRunawayException` fires (now soft-stop per #125), the framework auto-bumps complexity one tier before running the forced-synthesis turn. Captures the "I ran out of iterations on a Medium-tier model; let me try the synthesis on High" path without requiring the agent to explicitly escalate. Logged for visibility.

**App-side migration (Sage as the test case):**

```scala
// In Sage.scala, the routingStrategy becomes:
private val routingStrategy: Task[ProviderStrategy] = llamaProvider.map { llama =>
  val llamaC = ModelCandidate(
    llamaId,
    GenerationSettings(),
    supportedComplexity = Set(Complexity.Low)  // Qwen3.6-35B is fine for Low only
  )
  val gpt = ModelCandidate(
    Model.id("openai/gpt-5.5"),
    GenerationSettings(),
    supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High)
  )
  val claude = ModelCandidate(
    Model.id("anthropic/claude-opus-4-7"),
    GenerationSettings(),
    supportedComplexity = Set(Complexity.Low, Complexity.Medium, Complexity.High)
  )
  val cheapFirst    = List(llamaC, gpt, claude)
  val frontierFirst = List(gpt, claude, llamaC)
  ProviderStrategy.routed(
    default = cheapFirst,
    routes = Map(
      CodingWork       -> frontierFirst,
      CreativeWork     -> frontierFirst,
      AnalysisWork     -> cheapFirst,    // llama handles Low, gpt handles Medium/High
      ConversationWork -> cheapFirst,
      // ...
    ),
    // Single combined classifier call on the local llama:
    inferWorkType   = Some(SageRouter.classifyWorkType),
    inferComplexity = Some(SageRouter.classifyComplexity)
  )
}
```

Sage's `SageRouter.classify*` uses `ConsultTool.invoke` with a tight prompt asking llama to output `{workType, complexity}` JSON. Cost: ~50-100 tokens per user message, far less than handling the wrong-tier inference.

**Why this design over the alternatives discussed:**

- **vs. per-model `maxComplexity` capability metadata**: that approach would force someone (Sage author, OpenRouter, or Sigil) to maintain "what complexity does each model handle" as global truth. Different apps have different ceilings — Sage on Qwen-35B might cap at Low, an app on Qwen-72B might trust it for Medium. Encoding capability in `ModelCandidate` (per-strategy, app-controlled) is the right level of abstraction.
- **vs. classifier returns one combined `RoutingTier` enum**: a 1D tier (e.g. `T1 / T2 / T3`) collapses the WorkType signal. "T2-coding" and "T2-conversation" route the same — wrong, because CodingWork prefers frontier-first while ConversationWork prefers cheap-first even at the same complexity. The 2D model preserves both signals.
- **vs. per-iteration classification**: chosen the per-user-message-with-voluntary-reclassify shape because cost (one classifier call per user turn) is bounded, and the agent's `request_escalation` gives a clean escape valve for the drift case.

**Companion to existing bugs:**

- **#125 (cap-hit forced synthesis)**: composes cleanly — the cap-hit forced respond runs on the auto-escalated tier.
- **#127 (phase tracking)**: complementary. Phase says "gathering vs synthesizing"; complexity says "shallow vs deep." Together: the agent in `Synthesizing` phase + `High` complexity has the strongest framework-side telemetry to detect "should be writing now but isn't."
- **#109 (progress checkpoints)**: the checkpoint reflector can optionally read the current (WT, C) and adjust its prompt — different stuck thresholds for High vs Low complexity tasks.
