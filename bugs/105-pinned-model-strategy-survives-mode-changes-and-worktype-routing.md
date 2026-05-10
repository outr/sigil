# ❌ #105 — Pinned-model strategy: explicit `pin_model(modelId)` that survives mode changes, work-type routing, and curate / classifier auxiliary calls

**Where:** New `ProviderStrategy.pinned(modelId)` constructor
in
`core/src/main/scala/sigil/provider/ProviderStrategy.scala`
plus a `pin_model` tool (or `switch_model` extension) under
`core/src/main/scala/sigil/tool/provider/`.

**What's wrong:** Today's "switch model" semantics are
soft. `SwitchModelTool` creates an ad-hoc
`ProviderStrategyRecord` with a single `ModelCandidate`, but
the strategy still gets layered against:

  1. **Mode-driven work-type routing.** When the conversation
     enters `CodingMode` (`workType = CodingWork`) the
     dispatcher consults the strategy's `routes` map. If the
     ad-hoc strategy has only `defaultCandidates` and no
     coding-route override, dispatch may fall through to the
     framework's per-WorkType routing — picking gpt-5.5 for
     coding work, llama for conversation work.

  2. **Auxiliary calls.** Topic classifier, curate
     compression, memory extraction, and similar framework-
     internal LLM dispatches don't always honour the user's
     ad-hoc strategy — some pick model directly via app
     defaults.

Net effect: a user who explicitly switched to local llama
(or to a specific cloud model) still sees calls going to
other models depending on what mode they're in or which
auxiliary task fires. The user reasonably expects "I picked
this model — use this model for everything until I change
my mind." Today they don't get that.

**Suggested fix:** Three parts:

### 1. `ProviderStrategy.pinned(modelId)` constructor

```scala
object ProviderStrategy {
  ...
  /** A strategy that returns `modelId` for every dispatch
    * regardless of work-type. Has no `routes` overrides and
    * a single-element `defaultCandidates`. Built so the
    * `RoutedStrategy` materialization can never select a
    * different model — even on rate-limit cooldown, even on
    * a coding-mode turn when frontier-first would normally
    * apply, even on framework auxiliary calls.
    *
    * The candidate's pricing is read fresh from the registry
    * (so cost projection still works); only the routing is
    * pinned. */
  def pinned(modelId: Id[Model]): ProviderStrategy = ...
}
```

The implementation explicitly opts out of cooldown / fail-over
behavior — pinned means pinned. If the model fails, the
turn fails (no surprising mid-conversation provider switch).
That's the contract the user is asking for.

### 2. `pin_model` tool — explicit user-facing surface

```scala
case object PinModelTool extends TypedTool[PinModelInput](
  name = ToolName("pin_model"),
  description =
    """Pin all subsequent LLM calls in this conversation to a single model. Overrides
      |mode-driven routing, work-type routing, and ad-hoc switch_model strategies. Stays
      |in effect until `unpin_model` is called or the conversation is reset.
      |
      |Use when the user wants deterministic model selection (e.g., "always use local
      |qwen", "stay on gpt-5.5 even when classifier needs a small model").""".stripMargin,
  keywords = Set("pin", "lock", "force", "stick", "model", "fix", "always", "deterministic"),
  ...
) {
  override def requiresUserConsent: Boolean = false  // user-driven by definition

  override protected def executeTyped(input: PinModelInput, ctx: TurnContext): Stream[Event] =
    Stream.force(ctx.sigil.assignProviderStrategy(
      ctx.conversation.space,
      ProviderStrategy.pinned(input.modelId).id,
      ctx.chain
    ).map(_ => reply(ctx, s"Pinned to ${input.modelId.value}. All future calls use this model.")))
}
```

Companion `UnpinModelTool` clears the assignment (delegates
to `unassignProviderStrategy`), letting the default routing
strategy take over again.

### 3. Auxiliary-call pinning

Framework internal calls (topic classifier, curate, memory
extractor) currently bypass the strategy in some paths.
Audit and route them through the same `resolveProviderStrategy`
lookup so a pinned model affects every LLM dispatch in the
conversation, not just the agent's main turn.

```scala
// Before:
classifier.classify(turn, classifierModel)  // hardcoded model

// After:
sigil.resolveProviderStrategy(conv.space).flatMap {
  case Some(strategy) => classifier.classify(turn, strategy.dispatchFor(ClassificationWork))
  case None           => classifier.classify(turn, classifierModel)
}
```

So a user who pins to local llama gets every call — agent
turns, classifier, curate — running locally. A user who pins
to gpt-5.5 gets everything on gpt-5.5 (paying for the cost
of running a frontier model on classifier work, but at least
it's consistent and predictable).

### Test

```scala
class PinnedModelSpec extends AbstractSigilSpec {

  test("pinned model survives mode changes") {
    val convId = freshConversation()
    invokeTool(convId, "pin_model", Map("modelId" -> "local-llama"))
    
    invokeTool(convId, "change_mode", Map("mode" -> "coding"))
    val turn1Model = takeNextLlmDispatch().model
    assert(turn1Model == "local-llama", "coding mode must not override pin")
    
    invokeTool(convId, "change_mode", Map("mode" -> "conversation"))
    val turn2Model = takeNextLlmDispatch().model
    assert(turn2Model == "local-llama")
  }

  test("pinned model survives auxiliary classifier calls") {
    val convId = freshConversation()
    invokeTool(convId, "pin_model", Map("modelId" -> "gpt-5.5"))
    
    sendUserMessage(convId, "switch topics now")  // triggers classify_topic_shift
    val classifierDispatch = takeNextLlmDispatch()
    assert(classifierDispatch.model == "gpt-5.5",
      "classifier must use the pinned model, not its hardcoded default")
  }

  test("unpin restores default routing") {
    val convId = freshConversation()
    invokeTool(convId, "pin_model", Map("modelId" -> "local-llama"))
    invokeTool(convId, "unpin_model", Map())
    
    invokeTool(convId, "change_mode", Map("mode" -> "coding"))
    val model = takeNextLlmDispatch().model
    assert(model != "local-llama", "after unpin, coding mode picks frontier model again")
  }
}
```

This makes "pin to model X" a first-class, deterministic
operation. Apps register `PinModelTool` / `UnpinModelTool`
in their `staticTools` (Sage opts in immediately). Users get
the predictability they're asking for: when they pin, every
call really does use that model.
