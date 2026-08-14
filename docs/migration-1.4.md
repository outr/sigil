# Migrating from Sigil 1.3 to 1.4

Audience: app developers on `sigil` 1.3.x.

## 1. What 1.4 is

Six things changed shape:

- **Tool authoring surface.** A tool's capabilities used to be a dozen loose overridable
  members. In 1.4 they are two values: a `ToolSpec` (identity, capability profile, discovery)
  and a `ToolIO` (codecs, schema, examples). Body dispatch is one abstract `resolve`.
- **Typed wire dispatch.** A provider's tool call carries a `WireCall` — decoded, unresolved,
  or malformed — instead of an already-decoded `ToolInput`. A roster (`ToolRoster`) is built
  once per request and is the only name-resolution authority.
- **Memory pipeline.** Retrieval is a staged pipeline behind one shared recall gate; access
  counts are eventually consistent; extraction is filtered on both the per-turn and the
  compression leg; consolidation and embedding reconciliation are maintenance tasks.
- **Context as data.** The system prompt's sections are an ordered `List[ContextSection]` on
  `Sigil` that drives the renderer, the wire profiler, and the shed cascade from one list.
- **Governors.** Boundary decisions (spend budgets, stall detection, planner oversight) run
  through a `TurnGovernor` list that votes at each turn boundary.
- **Model profiles.** `ModelProfile` declares what a model can be trusted with; the framework
  uses it to size the `find_capability` roster and tighten oversight cadence.

Migration effort is **mechanical and header-only for tools**: the body of every tool is
unchanged, only its declaration header moves. Everything else in 1.4 is additive — `Sigil`
lost no public member; `Provider` lost no public member.

## 2. Tool authoring

### 2.1 Where each member went

| 1.3 member | 1.4 location | Still readable on `Tool`? |
|---|---|---|
| `name`, `description` | `spec.name`, `spec.description` | yes, `def` (non-final for record round-trip) |
| `keywords`, `space`, `modes` | `spec.discovery.*` | yes, `def` (non-final) |
| `kind`, `toolchain`, `preferIfNoBetter`, `suggestedNextTools` | `spec.discovery.*` | yes, `final def` |
| `readOnly`, `destructive` | `spec.profile.effect` (`Effect.ReadOnly` / `Mutating` / `Destructive`) | yes, `final def` |
| `mutationTarget(input)` | `Effect.Mutating(MutationTargeting.…)` / `Effect.Destructive(…)` | `mutationTargetOf` is `private[sigil]` |
| `detachable`, `detachedKeepRunningOnStop` | `spec.profile.execution` (`Execution.Detachable`) | yes, `final def` |
| `requiresUserConsent`, `preconditions`, `requiresAccessibleSpaces` | `spec.profile.gates` (`ToolGates`) | yes, `final def`; plus new `consentPrompt` |
| `boundsOutputItself` | `spec.profile.output` (`OutputBounds.SelfBounded`) | yes, `final def` |
| `inputRW`, `outputRW`, `inputDefinition`, `outputDefinition`, `examples` | `io` (`ToolIO`) | yes; all `final` except `examples` |
| `executeResult` / `executeOutput` | `resolve` (`Resolution.Explicit` / `Resolution.Simple`) | no — replaced |
| `idempotent`, `openWorld`, `resultTtl`, `destructivePrefix` | removed | no |
| the `ToolAnnotationMixins` traits (`ReadOnlyExternalTool`, `DestructiveInternalTool`, …) | removed — declare `Effect` on the spec | no |

`resultTtl` has a successor in spirit: `Effect.ReadOnly(Freshness.Pure | Stable | Volatile)`
drives turn-scoped read caching (§5).

### 2.2 The two new values

```scala
final case class ToolSpec private (name: ToolName, description: String,
                                   profile: ToolProfile, discovery: DiscoverySpec)

final case class ToolProfile(effect: Effect,
                             execution: Execution = Execution.Inline,
                             gates: ToolGates = ToolGates.none,
                             output: OutputBounds = OutputBounds.FrameworkBounded)

enum Effect {
  case ReadOnly(freshness: Freshness)
  case Mutating(target: MutationTargeting)
  case Destructive(target: MutationTargeting, consequence: String)
}

final case class DiscoverySpec(keywords: Set[String] = Set.empty,
                               space: SpaceId = GlobalSpace,
                               modes: Set[Id[Mode]] = Set.empty,
                               preferIfNoBetter: Boolean = false,
                               toolchain: Option[String] = None,
                               suggestedNextTools: List[ToolName] = Nil,
                               kind: ToolKind = BuiltinKind)
```

`ToolSpec.apply` is a validating factory (the constructor is private). It throws
`ToolSpecException` collecting every violation: blank description, description over
`ToolSpec.DescriptionBudget` (4096) chars, `Effect.Destructive` with a blank `consequence`,
`Execution.Detachable` with a blank `ProgressContract.story`, a consent gate with a blank
`ConsentSpec.prompt`, and a discoverable `kind` with empty `keywords`.

`ToolIO` has a private constructor and four factories, each fail-fast with `ToolIOException`:

```scala
ToolIO.derived[I, O]                     // both sides derived; runs the schema-ergonomics lint
ToolIO.dynamic(definition)               // JsonInput + TextToolOutput over a runtime Definition
ToolIO.dynamicAs[O](definition)          // JsonInput + a typed output
ToolIO.withSchema[I, O](definition)      // typed input, hand-written schema (checked against the RW)
```

Examples attach through `io.withExamples(…)`, which round-trips each example through the
schema and names the failing one.

### 2.3 Example — a static `case object` tool

**1.3**

```scala
case object LookupOrderTool extends Tool {
  type Input  = LookupOrderInput
  type Output = LookupOrderOutput
  val inputRW: RW[LookupOrderInput]   = summon[RW[LookupOrderInput]]
  val outputRW: RW[LookupOrderOutput] = summon[RW[LookupOrderOutput]]

  val name        = ToolName("lookup_order")
  val description = "Look up an order by id and report its status and total."
  override def keywords: Set[String] = Set("order", "lookup", "status")
  override def space: SpaceId        = GlobalSpace
  override def readOnly: Boolean     = true

  override def executeOutput(input: LookupOrderInput, context: ToolContext): Task[LookupOrderOutput] =
    Task.pure(LookupOrderOutput(status = s"shipped:${input.orderId}", total = 42.0))
}
```

**1.4**

```scala
case object LookupOrderTool extends Tool {
  type Input = LookupOrderInput
  type Output = LookupOrderOutput

  val io: ToolIO[LookupOrderInput, LookupOrderOutput] = ToolIO.derived[LookupOrderInput, LookupOrderOutput]

  val spec: ToolSpec = ToolSpec(
    name = ToolName("lookup_order"),
    description = "Look up an order by id and report its status and total.",
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(keywords = Set("order", "lookup", "status"), space = GlobalSpace)
  )

  protected def resolve: Resolution[Input, Output] = Resolution.Simple { (input, _) =>
    Task.pure(LookupOrderOutput(status = s"shipped:${input.orderId}", total = 42.0))
  }
}
```

`Resolution.Simple` replaces `executeOutput`; `Resolution.Explicit` replaces `executeResult`
(and is what you want whenever the body returns `ToolResult.Failure` for a logical failure).

### 2.4 Example — a dynamic `case class` record tool

Persisted tool records keep `derives RW` and keep overriding `name` / `description` /
`keywords` / `space` / `modes` as constructor fields, so the stored field names round-trip.
The spec is derived from those fields in the body — which also means the persisted row is
re-validated on every load.

**1.3**

```scala
case class SavedQueryTool(override val name: ToolName,
                          override val description: String,
                          sql: String,
                          parameters: Definition,
                          override val space: SpaceId,
                          override val keywords: Set[String] = Set.empty,
                          override val modes: Set[Id[Mode]] = Set.empty,
                          override val createdBy: Option[ParticipantId] = None,
                          override val created: Timestamp = Timestamp(),
                          override val modified: Timestamp = Timestamp())
  extends Tool derives RW {

  override val _id: Id[Tool] = Id[Tool](s"saved-query::${space.value}::${name.value}")

  type Input  = JsonInput
  type Output = SavedQueryOutput
  override val inputRW: RW[JsonInput]        = summon[RW[JsonInput]]
  override val outputRW: RW[SavedQueryOutput] = summon[RW[SavedQueryOutput]]
  override def inputDefinition: Definition   = parameters
  override def readOnly: Boolean             = true

  override def executeResult(input: JsonInput, context: ToolContext): Task[ToolResult[SavedQueryOutput]] =
    Task.pure(ToolResult.Success(SavedQueryOutput(List(sql, input.json.toString))))
}
```

**1.4**

```scala
case class SavedQueryTool(override val name: ToolName,
                          override val description: String,
                          sql: String,
                          parameters: Definition,
                          override val space: SpaceId,
                          override val keywords: Set[String] = Set.empty,
                          override val modes: Set[Id[Mode]] = Set.empty,
                          override val createdBy: Option[ParticipantId] = None,
                          override val created: Timestamp = Timestamp(),
                          override val modified: Timestamp = Timestamp())
  extends Tool derives RW {

  val spec: ToolSpec = ToolSpec(
    name = name,
    description = description,
    profile = ToolProfile(effect = Effect.ReadOnly(Freshness.Volatile)),
    discovery = DiscoverySpec(keywords = keywords, space = space, modes = modes)
  )

  override val _id: Id[Tool] = Id[Tool](s"saved-query::${space.value}::${name.value}")

  type Input = JsonInput
  type Output = SavedQueryOutput

  val io: ToolIO[JsonInput, SavedQueryOutput] = ToolIO.dynamicAs[SavedQueryOutput](parameters)

  protected def resolve: Resolution[Input, Output] = Resolution.Explicit { (input, _) =>
    Task.pure(ToolResult.Success(SavedQueryOutput(List(sql, input.json.toString))))
  }
}
```

A record whose persisted fields can no longer satisfy `ToolSpec.apply` (a row written before a
validation rule existed) will throw on load. Repair in the spec builder rather than in the
constructor — see `sigil.script.ScriptTool.specFor`, which substitutes a stub description and
falls back to the tool name for keywords, logging a warn each time.

### 2.5 Example — a decorated / proxied tool

`ToolDecorator` is new. It forwards spec, `io`, identity, and record metadata from **one**
value, `underlying`. A decorator overrides only `resolve`, so a capability added to
`ToolProfile` later reaches every decorator automatically.

**1.3** — every forwarded member declared by hand (this is `ProxyTool` as it shipped):

```scala
class ProxyTool(val wrapped: Tool, transport: ToolProxyTransport) extends Tool {
  type Input  = wrapped.Input
  type Output = wrapped.Output

  def inputRW: RW[Input]   = wrapped.inputRW
  def outputRW: RW[Output] = wrapped.outputRW
  def name: ToolName       = wrapped.name
  def description: String  = wrapped.description

  override def inputDefinition: Definition                        = wrapped.inputDefinition
  override def outputDefinition: Option[Definition]               = wrapped.outputDefinition
  override def modes: Set[Id[Mode]]                               = wrapped.modes
  override def space: SpaceId                                     = wrapped.space
  override def keywords: Set[String]                              = wrapped.keywords
  override def examples: List[ToolExample]                        = wrapped.examples
  override def createdBy: Option[ParticipantId]                   = wrapped.createdBy
  override def _id: Id[Tool]                                      = wrapped._id
  override def created: Timestamp                                 = wrapped.created
  override def modified: Timestamp                                = wrapped.modified
  override lazy val schema: ToolSchema                            = wrapped.schema

  override def executeResult(input: Input, context: ToolContext): Task[ToolResult[Output]] = …
}
```

**1.4**

```scala
class ProxyTool(val underlying: Tool, transport: ToolProxyTransport) extends ToolDecorator {
  protected def resolve: Resolution[Input, Output] = Resolution.Explicit { (input, context) =>
    transport.dispatch(underlying.name, inputRW.read(input), context).map {
      case ToolResult.Success(json)    => ToolResult.Success(outputRW.write(json))
      case failure: ToolResult.Failure => failure
    }
  }
}
```

Any decorator follows the same shape:

```scala
class AuditedTool(val underlying: Tool, audit: String => Task[Unit]) extends ToolDecorator {
  protected def resolve: Resolution[Input, Output] = Resolution.Simple { (input, context) =>
    audit(s"${underlying.name.value} called by ${context.caller.value}")
      .flatMap(_ => underlying.invoke(input, context))
  }
}
```

The shipped `ProxyTool` keeps `def wrapped: Tool = underlying`, so 1.3 call sites reading
`.wrapped` still work; the constructor parameter name changed to `underlying`, which matters
only for named-argument construction.

## 3. Removed and changed public surface

Each entry names the fix.

### Tools

- **`executeResult` / `executeOutput` → `resolve`.** `protected def resolve: Resolution[Input, Output]`
  is abstract. `Resolution.Simple(run: (I, ToolContext) => Task[O])`,
  `Resolution.Explicit(run: (I, ToolContext) => Task[ToolResult[O]])`. There is no
  `ToolResolution` type. *Fix:* wrap the old body in the matching case.
- **`inputRW` + `outputRW` → `io`.** `def io: ToolIO[Input, Output]` is abstract; `inputRW`,
  `outputRW`, `inputDefinition`, `outputDefinition` and the new `wireSurface` are `final`
  derivations of it. *Fix:* `val io = ToolIO.derived[I, O]`; for a runtime schema use
  `ToolIO.dynamicAs` / `ToolIO.withSchema` instead of overriding `inputDefinition`.
- **Deleted members:** `idempotent`, `openWorld`, `resultTtl`, `mutationTarget(input)`,
  `destructivePrefix`, `runResolution`, `buildResultDelta`. *Fix:* drop them; for
  `mutationTarget` declare `Effect.Mutating(MutationTargeting.typed[MyInput](…))` or
  `MutationTargeting.path[MyInput](…)` on the spec; for `destructivePrefix` supply
  `Effect.Destructive(target, consequence)` — the consequence string becomes the wire warning.
- **Deleted file `ToolAnnotationMixins.scala`** (`ReadOnlyExternalTool`, `ReadOnlyInternalTool`,
  `DestructiveExternalTool`, `DestructiveInternalTool`, `NetworkReadOnlyTool`). *Fix:* express
  the same thing as an `Effect` on the spec.
- **`Tool.invoke` is now `final`**, and **`Tool.summarize` is public** (was `protected`).
  *Fix:* remove an `invoke` override; a `summarize` override needs no `protected`.
- **`Tool.kind` is now `final`.** *Fix:* set `DiscoverySpec(kind = MyKind)`.
- **`ToolName` is an opaque type.** `inline def apply[S <: String & Singleton](inline value: S)`
  checks the literal against `ToolName.LiteralGrammar` (`[a-z][a-z0-9_]{0,63}`) at compile
  time; `ToolName.parse(value): Either[String, ToolName]` handles runtime strings against
  `DynamicGrammar` (`[a-zA-Z0-9_-]{1,64}`). Wire shape is unchanged (a bare string). *Fix:*
  `ToolName("literal")` still compiles if the literal matches the grammar; `ToolName(someVar)`
  becomes `ToolName.parse(someVar)`; `ToolName(x).copy(…)` and `case ToolName(v) =>` have no
  replacement — use `.value`.
- **`ToolExample` gained a type parameter:** `case class ToolExample[+I <: ToolInput](description: String, input: I)`.
  *Fix:* attach through `io.withExamples(…)` rather than overriding `examples` with a
  heterogeneous list.
- **`ToolFinder.toolInputRWs → toolIO`:** `def toolIO: List[ToolIO[?, ?]]`. Output codecs now
  register symmetrically with input codecs. *Fix:* return `tools.map(_.io)`. Also note
  `InMemoryToolFinder.byName` is exact-match now (was case-insensitive), matching
  `DbToolFinder`.
- **`Tool` record index rename:** the indexed field is `spaceId` (singular), projecting
  `_.space.value`. *Fix:* update any hand-written query against `Tool.spaceIds`.

### Provider / wire

- **`ProviderCall.tools: Vector[Tool]` → `roster: ToolRoster`.** `ToolRoster` is built once per
  request (`ConversationRequest.roster`) and is the sole name-resolution authority
  (`resolve`, `contains`, `size`). *Fix:* pass `ToolRoster(myTools)`; read `call.tools` (a
  convenience `def` returning `roster.tools`) where you only need the vector.
- **`ProviderEvent.ToolCallComplete(callId, input: ToolInput)` → `ToolCallComplete(callId, call: WireCall)`.**
  `WireCall` is `Decoded(DecodedCall) | Unresolved(name, rawArgs) | Malformed(name, error, rawArgs)`;
  deserialization always yields `Unresolved` and `rebind(roster)` restores it. *Fix:* build with
  `ProviderEvent.toolCall(callId, tool)(input)`; read with `call.decodedInput` /
  `call.inputFor(tool)`; match on `Malformed` where you previously had no representation for a
  bad call.
- **`Provider.schemaDialect` added** (`def schemaDialect: SchemaDialect = SchemaDialect.Identity`).
  This is the only public change on `Provider`. *Fix:* a custom provider that hand-rolled
  strict-mode / Gemini schema munging should delete that code and declare the dialect;
  `SchemaDialect.apply(tool)` and `strictForTool(tool)` own the per-tool decision.
- **`emergencyShed` essentials** now derive from `RespondFamilyTool.names`, so apps that ship
  `respond_card` / `respond_cards` no longer lose them under budget pressure. No action needed.

### Consults

- **`ConsultTool.invoke` / `invokeRich` / `invokeRouted` are path-dependent, not `ClassTag`-driven.**
  The tool parameter is `tool: Tool { type Input = I }` (and
  `(Tool { type Input = I }) & FrameworkConsult` for `invokeRouted`). *Fix:* drop the explicit
  type argument and the `ClassTag` — `I` is inferred from the tool. If inference fails, the
  tool's declared `Input` genuinely differs from what you asked for.
- **`ConsultOutcome.Unparseable(error: DecodeError)` added.** *Fix:* handle it in exhaustive
  matches; it separates "the model emitted args the schema rejects" from `Failed`.

### Turn context

- **`TurnContext.correlationId` and `TurnContext.freshCorrelationId()` are gone**, with no
  replacement field. *Fix:* if you correlated logs through it, thread your own id through the
  app-side ingress. (The `correlationId` fields on `ConversationHealed` /
  `ConversationCorruptionDetected` / `HealingExhausted` are unrelated and unchanged.)
- **`ToolContext` emission state.** `emittedEventsRef` is replaced by
  `emissionState: AtomicReference[Option[Vector[Event]]]` (`Some` = open, `None` = closed).
  `ctx.emit` after the tool settles now raises `LateEmissionException` instead of silently
  dropping the event. *Fix:* emit before returning from `resolve`.

### Context sections and profiling

- **The system prompt's section taxonomy moved from inside `Provider.renderSystem` onto
  `Sigil`.** `def contextSections: List[ContextSection] = ContextSections.all` (`Sigil`) is
  now the single list driving the renderer, the wire profiler, and the shed cascade.
  `Provider` never had a public `contextSections` member — the taxonomy was hard-coded and
  duplicated in `RequestProfiler`. *Fix:* nothing, unless you patched the prompt layout; then
  override `contextSections` (§6).
- **`RequestProfiler` signature.** `profile(request, resolved, tokenizer, sigil)` →
  `profile(ctx: SectionContext, tokenizer, sigil, sections = ContextSections.all)`;
  `profileWith(request, resolved, tokenizer, descriptionFor)` →
  `profileWith(sectionContext, tokenizer, descriptionFor, sectionList = ContextSections.all)`.
  *Fix:* build a `SectionContext` (the shared per-turn derivation bundle) instead of passing
  the request + resolved references separately.
- **`sigil.tool.model.ContextSectionKind` deleted.** *Fix:* use `ProfileSection`, now the
  single section discriminator.

### Startup

- **`Sigil.staticTools` is read exactly once and memoized.** The framework's access path is
  the new `final def resolvedStaticTools`; registration, the static-tool sync upgrade, and the
  suggestion cascade all see the same instances, so a tool holding mutable state (e.g. a
  `ProcessRegistry`) behaves even when constructed inline in the override. *Fix:* call
  `resolvedStaticTools`, not `staticTools`, from app code after boot. Hoisting stateful values
  to a `private lazy val` in your override is still the clearer style.
- **A boot completeness pass runs at the end of `Sigil.polymorphicRegistrations`.** It is
  tool-list + RW-registration only (no store access), so codegen flows run it too. It collects
  every violation and throws one `ToolRegistrationException`. What a migrating app sees first,
  and the fix for each class:

  | Message | Cause | Fix |
  |---|---|---|
  | `duplicate tool name '<n>' in the registered roster` | two distinct tools share a name (re-listing the same value is fine) | rename one |
  | `tool '<t>' declares suggestedNextTools '<s>', which does not resolve against the registered tool set` | a cascade points at an unregistered tool | register the target, or drop it from `DiscoverySpec.suggestedNextTools` |
  | `registered tool <input\|output> types collide on the simple name '<simple>': <fqcns> — fabric dispatches polymorphic reads by lowercased simple name, so one silently shadows the other. Rename one of the types.` | two `ToolInput` (or `ToolOutput`) types share a simple class name across packages | rename one type |
  | `tool '<t>' overrides \`name\` away from its spec name '<spec.name>' — the record id, consent lookup, and roster resolution key off different values` (and the same for `description`, `keywords`, `space`, `modes`) | a partial migration where the header still overrides a member the spec also declares | delete the override, or make the spec agree |
  | `tool '<t>' <input\|output> <Class> is not registered with the polymorphic RW: …` | the `ToolInput` / `ToolOutput` subtype is missing from `findTools.toolIO` (or the tool isn't in `staticTools`) | add the tool to `staticTools`, or its `io` to your finder's `toolIO` |
  | `tool '<t>' <input\|output> <Class> failed the polymorphic RW round-trip and its discriminator does not dispatch: …` | registered, but the codec can't round-trip | fix the RW — usually a field type with no `RW` in scope |

  A probe that fails only because a refined field type rejects a synthesized value is not a
  violation; it is downgraded to a debug line. Output probing is skipped when the declared
  `Output` is the open `ToolOutput` base.
- **`ContextSections.shedCascade` is validated at `Sigil.instance`.** A section declaring a
  `shedStage` with no `shed` effect fails startup with:

  ```
  ContextSection <id> declares shedStage=<n> but carries no `shed` effect — a shed stage
  the curator cannot apply is a silent no-op. Provide `shed`, or drop `shedStage`.
  ```

- **New exceptions reachable at construction / boot:** `ToolSpecException`, `ToolIOException`,
  `ToolRegistrationException`, `LateEmissionException`.

### MCP

- **`McpTool.resolveNames(rawNames): Map[String, ToolName]`** is new. Server tool names that
  sanitize to the same `ToolName` now warn and get distinct (sorted, stable) names instead of
  one silently shadowing the other. `McpToolFinder` resolves once and threads the map through
  listing and by-name lookup. No app action required.

### Client tools

- **`ClientToolSpec` gained `consequence: Option[String] = None`** (after `destructive`).
  Positional construction still compiles; supply it for a destructive client tool so the wire
  warning is specific.

### `move_memory` input takes space value strings

`MoveMemoryInput.newSpace` (and `fromSpace`) are `String` — the target space's `value` — resolved server-side against the caller's accessible spaces. In 1.3 they were typed `SpaceId` fields, which required the model to construct a discriminated union and made the tool's schema depend on the app's registered space subtypes. A miss returns a recoverable failure listing the accessible values.

### Preview display envelopes honour StreamConfig.maxWidth/maxHeight

The stream browser's framebuffer is sized from the declared `maxWidth`/`maxHeight` (falling back to the render target), so a preview that starts small can grow to fullscreen via `resizePreview` without a relaunch; targets beyond the envelope are clamped and served with a warning instead of aborting. Consumers that pre-allocated an oversized display as a workaround can pass the envelope as `max*` and the real pane as `width`/`height`.

### Client tools reach both host shapes

UI-registered client tools (`RegisterClientTools`) are reachable on every host: discovery-enabled hosts find them through `find_capability` (unchanged), and hosts whose effective roster carries no `find_capability` (`ToolPolicy.ActiveOnly` / `None`, or an override that filters it) now get them injected into the roster directly. Discovery-first hosts see no roster change; discovery-off hosts no longer need a `conversationToolOverlays` workaround.

### Schema-ergonomics rule is enforced at boot

The required-union rule runs in the boot completeness pass against the final registered polymorphic state, not at `ToolIO.derived` construction — a union's shape depends on which subtypes the app registers, so construction-time verdicts were registration-order-dependent. `ToolIO.withSchema` / `dynamic*` remain the recorded opt-outs. Practically: an app tool with a required `SpaceId`-style union field now fails at startup with a named violation instead of a class-initialization error at first touch.

## 4. Behavior changes to know

- **Freshness-derived read caching.** A `ReadOnly(Freshness.Pure)` or `ReadOnly(Freshness.Stable)`
  tool's result is cached for the turn (`TurnContext.toolResultCacheRef`). `Volatile` is never
  cached; mutating calls are never cached. Any mutating call clears every `Stable` entry
  unconditionally; `Pure` entries survive. Declaring `Volatile` is the conservative choice when
  in doubt — it is the pre-1.4 behavior.
- **Duplicate cap on served repeats.** Every early-return dispatch path that produces a real
  result — a served cache hit, an inlined duplicate, a raced re-issue handed a settled result —
  now settles its `ToolInvoke` with `ToolOutcome.Success` instead of leaving it `Pending`. A
  *refused* dispatch settles no outcome. Knobs: `maxIdenticalToolCallsInWindow` (3),
  `maxRacedReissues` (2), `maxToolCallsPerResponse` (8), `recentToolInvocationsLimit` (20).
- **Workflow steps honor gates and publish emitted events.** A `SigilJobStep` dispatches through
  `ToolExecutor.executeCollected` with `GateContext.Gated`, so preconditions and consent
  genuinely gate a workflow step. The tool's drained `ctx.emit` events publish through
  `host.publish` in order, awaited, before the settled invoke — which now carries the dispatch's
  own `invokeId`, so `origin` stamps resolve. A per-event publish failure is logged and skipped,
  never failing the step.
- **Memory recall gate.** `ContextMemory.isRecallable(now)` (current version, `Approved`, not
  expired) is applied by every retrieval surface — the retriever stages, `searchMemories`,
  `findMemories`, `resolveReferences`, the curator, `lookup`. Superseded, pending, rejected, and
  expired records cannot reach a prompt. Not configurable.
- **Eventually-consistent access counts.** Retrieval accumulates `accessCount` /
  `lastAccessedAt` bumps in memory; `MemoryAccessFlushTask` drains them every
  `memoryAccessFlushInterval` (60s, in the default `maintenanceTasks`) and once at `shutdown`,
  using `tx.modify` so the write lands on the fresh row and touches only those two fields. A
  process killed between flushes loses at most one interval of counts.
- **Extraction filter applies on both legs.** `MemoryExtractor.signalFilter` is consulted by the
  compression-time extractor as well as the per-turn one; `StandardMemoryExtractor` makes its
  own `filter` authoritative on both. `extractFromFrames` now supplies `settledMutations`, so a
  mutation-aware filter judges a shed slice by what it did. A custom extractor that leaves
  `signalFilter = None` extracts exactly as it did in 1.3.
- **Consolidation is opt-in.** `MemoryConsolidationTask` is not in the default
  `maintenanceTasks`; it spends LLM calls and rewrites memory rows, so activation is an app
  decision. It also no-ops with a debug log unless vector search is wired.
- **`find_capability` roster sizing.** The returned roster is sized to the running model:
  `min(ModelProfile.contextComfort, model.contextLength)` sets both a rendered-bytes budget and
  a count ceiling (3–25, further capped by `InstructionTier.rosterCountCeiling` — 8 for `Small`,
  5 for `Minimal`). Lowest-scored matches drop first; at least one always survives. A
  small-context model now gets a roster that fits with room to act instead of a truncated one.

## 5. New opt-in capabilities worth adopting

- **`ModelProfile` for small models.** `def modelProfileFor(model: Model): ModelProfile` on
  `Sigil` defaults to `ModelProfile.heuristic`, which infers from parameter count and frontier
  family. Declare it explicitly for models you actually run:

  ```scala
  override def modelProfileFor(model: Model): ModelProfile =
    if (model._id.value.contains("my-8b")) ModelProfile(
      instructionTier     = InstructionTier.Small,
      toolCallReliability = Reliability.Wobbly,
      contextComfort      = 16000,
      needsOversight      = true,
      promptShape         = PromptShape.Compact
    )
    else super.modelProfileFor(model)
  ```

  `InstructionTier` tightens progress-checkpoint and planner cadence
  (`cadenceTightening` 1/1/2/4) and caps the discovery roster; `PromptShape.Compact` caps
  entries / memories / summaries / skills at 5 / 3 / 3 / 4; `needsOversight` arms the planner
  checkpoint.
- **Planner tier.** `plannerModelId: Option[Id[Model]]` (default `None`) plus
  `plannerCadence` (24, tightened by the profile). A sparse checkpoint runs
  `PlannerVerdictTool` against an explicit plan and feeds a typed `Directive` back into the
  loop.
- **Spend budgets.** `turnCostSoftBudget` / `turnCostHardCeiling` /
  `conversationCostSoftBudget` / `conversationCostHardCeiling` (all `None` by default), plus
  per-conversation `ConversationBudget` and the agent-callable `set_budget`. `BudgetGovernor`
  turns them into soft check-ins and hard ceilings at turn boundaries.
- **`TurnGovernor`.** `protected def turnGovernors: List[TurnGovernor]` defaults to the budget
  and progress governors. The first non-`Proceed` vote wins, so prepending your own governor
  preempts the built-ins.
- **`AgenticSignalFilter`.** A `HighSignalFilter` tuned for agentic transcripts (what a turn
  *did*, not what it said). Wire as
  `StandardMemoryExtractor(filter = HighSignalFilter.any(DefaultHighSignalFilter, AgenticSignalFilter))`.
- **Memory consolidation.** Append `new MemoryConsolidationTask(spaces, fallbackModelId, chain)`
  to `maintenanceTasks` to merge near-duplicate memories through the versioning machinery
  (nothing is hard-deleted; the recall gate hides superseded members).
- **Per-section shaping.** Override `contextSections` to reorder, drop, or add a system-prompt
  section. Each `ContextSection` carries its `ProfileSection` id, a `Placement`
  (`StablePrefix` — inside the cached prefix — or `VolatileTail`), an optional `shedStage`, a
  `render: SectionContext => Option[String]`, and the matching `shed: TurnInput => TurnInput`.
  Declaring a `shedStage` without a `shed` fails startup, so the renderer and the shedder can't
  drift apart.
- **Reply suggestions.** `replySuggestions: Option[ReplySuggestionsConfig]` (default `None`).
  Set it and every turn that settles with a user-visible reply fires a cheap background
  consult predicting what the person types next, delivered as a transient
  `SuggestedReplies(conversationId, forMessageId, suggestions)` notice — never persisted,
  never replayed. `count = 1` (the default) phrases the single most likely message as inline
  type-ahead for a composer; `count > 1` asks for that many candidates with distinct intents,
  for a chip UI. Worker scratchpads, staging conversations, and (by default) turns that
  already offered `respond_options` are skipped; `promptOverride` takes the assembled
  `ReplySuggestionContext` when you want your own phrasing.
- **Embedding reconciliation.** `EmbeddingReconcileTask` ships in the default
  `maintenanceTasks`. Every memory index write stamps `ContextMemory.embedding` with the
  embedder id, dimensionality, and a SHA-256 of the embedded text; the sweep finds rows whose
  stamp no longer matches and re-embeds them. It costs one empty indexed query when nothing has
  drifted and no-ops entirely without vector wiring. A custom `EmbeddingProvider` should
  override `def id: String` to include its model name — the default is the class simple name,
  which cannot detect a model swap.
- **Browser preview streaming.** The new `sigil-browser-stream` module adds
  `StreamBrowserSigil` on top of `BrowserSigil`: `previewStreamFor(conversationId)` starts a
  live preview of the conversation's browser on a dedicated headful, virtual-display browser,
  leaving the headless automation browsers untouched. It returns a WebRTC session (hardware
  H.264, viewer input over the session's DataChannel) where GStreamer and a display are
  available, and falls back to the CDP screencast on the same browser where they aren't —
  `streamFallbackToScreencast = false` turns the degradation into a `StreamUnavailableException`
  instead. WebRTC signaling rides the notice vocabulary (`PreviewSignal` out,
  `PreviewSignalReply` in, both conversation-scoped), so no new transport is needed. GStreamer
  stays out of every other module's dependency graph; see `browser-stream/README.md` for the
  runtime requirements.
- **Preview sizing and live resize.** A preview's resolution is a per-stream choice rather than
  the virtual display's: `StreamConfig.width`/`height` set the size the page lays out at and the
  exact rectangle that is streamed, so a `390x844` request previews a portrait, mobile-layout
  page with no letterboxing on either rung (a WebRTC session crops its display capture; the
  screencast applies the same size as a device-metrics override). `maxWidth`/`maxHeight` keep
  their old meaning as an encode-time downscale on top. `resizePreview(conversationId, width,
  height)` changes the size mid-preview — a WebRTC session renegotiates and its new offer arrives
  as another `PreviewSignal` on the same `streamId`, so viewers answer it over the plumbing they
  already have; a screencast session restarts capture behind the same `frames` stream. Apps that
  built their own resize by stopping and restarting a preview can drop it. Sizing a *fresh*
  preview browser now grows its virtual display to cover both the request and
  `streamBrowserConfig`'s configured size, since Xvfb refuses to resize a running display; apps
  that override `streamBrowserConfig` with a deliberately small display should size it to the
  largest preview they will ask for.
