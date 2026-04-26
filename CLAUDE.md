# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test

Scala 3.8.3 on sbt. The root project is `sigil`; there are two other projects: `benchmark` (memory-retrieval runners) and `docs` (mdoc).

- Compile: `sbt compile`
- All tests: `sbt test`
- One suite: `sbt "testOnly spec.OrchestratorTopicSpec"`
- One test inside a suite: `sbt "testOnly spec.OrchestratorTopicSpec -- -z 'substring from test name'"`
- CI-equivalent run (skip tests tagged `spec.LocalOnly`, which hit external services): `sbt 'testOnly -- -l spec.LocalOnly'`
- Benchmarks: see `benchmark/README.md`; runners are `sbt "benchmark/runMain bench.LongMemEvalBench ..."` and friends. Require a reachable Qdrant and an `OPENAI_API_KEY`.

Build-config invariants you should know before changing anything in `build.sbt`:

- `Test / parallelExecution := false` **and** `testGrouping` forks one JVM per spec. This is deliberate — RocksDB holds an exclusive lock on its directory and fabric `PolyType` registrations are process-global. Per-suite forks give each spec a clean process; sequential execution stops suites from racing for the same RocksDB path. Don't "optimize" this to parallel.
- `fork := true` at root and in `benchmark`. Tests always run in a forked JVM.

## Running against a local model

Many specs prefer live end-to-end runs over mocks (see user memory `feedback_live_tests_preferred.md`). The convention is a local llama.cpp server:

- Default host: `https://llama.voidcraft.ai` (publicly reachable; tests and CI hit it directly)
- Override for local dev: Profig key `sigil.llamacpp.host` or env `SIGIL_LLAMACPP_HOST` (e.g. `http://localhost:8081`)
- Specs that need it: `LlamaCpp*Spec.scala`. They skip gracefully when the server is unreachable.

HTTP wire logs are written per-suite under `target/wire-logs/<SuiteName>.jsonl` via `sigil.provider.debug.JsonLinesInterceptor`. Override the directory with `sigil.wire.log.dir` / `SIGIL_WIRE_LOG_DIR`. These are invaluable for debugging provider-stream issues.

## Storage

Default is on-disk RocksDB + Lucene via `SplitStoreManager`; DB path is `sigil.dbPath` (Profig). Set `sigil.postgres.jdbcUrl` to switch the whole store layer to Postgres (`PostgreSQLStoreManager`). Tests use per-suite paths under `db/test/<SimpleClassName>` and wipe them on init — `TestSigil.initFor(getClass.getSimpleName)` is the entry point.

## Architecture (big picture)

### `Sigil` trait — the orchestration hub

`src/main/scala/sigil/Sigil.scala` is the central trait every app extends. It owns:

- The **signal pipeline** — `publish(signal)` is the single ingress. In order per signal: persist via `SigilDB`, update projections on `Conversation`, append to `ConversationView`, apply Mode-source skill slot on settled `ModeChange`, dispatch `Stop` to in-flight agents, broadcast, then fan out to participants whose `TriggerFilter` matches.
- The **agent self-loop** — `tryFire` claims an `AgentState(Active)` lock via `tx.modify` on a deterministic lock id (`agentlock:<agentId>:<convId>`). The winner runs `runAgentLoop` on a background fiber, checking for new triggers between iterations without releasing the claim until settled. `maxAgentIterations` (default 10) hard-caps runaways with `AgentRunawayException`.
- **Polymorphic type registration** — `signals`, `participants`, `participantIds`, `memorySpaceIds`, plus `findTools.toolInputRWs`. These feed fabric `PolyType.register` at `instance` initialization. You MUST list app-specific subtypes here or fabric RW will fail to round-trip them.

Apps extend `Sigil` and provide: `findTools`, `curate`, `getInformation`/`putInformation`, `modeSkill`, `compressionMemorySpace`, `providerFor`, `broadcaster`, `wireInterceptor`, `embeddingProvider`, `vectorIndex`. See `README.md` for the minimal shape.

### Event-sourced conversations

- `Event` (Message, ToolInvoke, ToolResults, TopicChange, ModeChange, Stop, AgentState) is the source of truth — stored as `SigilDB.events`.
- `Signal = Event | Delta`. `Delta`s (ContentDelta, MessageDelta, ToolDelta, StateDelta, AgentStateDelta) are in-flight updates to an `Active` event; they settle it to `Complete`.
- `ConversationView` is the projection: a list of `ContextFrame`s built by `FrameBuilder` one-for-one from `Complete` events, plus per-participant `ParticipantProjection` (active skills, extra context, suggested tools). It's maintained by `Sigil.publish` idempotently and rebuildable from scratch via `Sigil.rebuildView`.
- `curate(view, modelId, chain) => TurnInput` runs on every turn. Apps decide which memories/summaries/information to surface. There is no implicit history truncation — the curator owns policy.

### Signal pipeline

`publish(signal)` is the single ingress; it is a three-level pipeline plus framework-internal steps:

1. **Inbound transforms** (`Sigil.inboundTransforms: List[InboundTransform]`) — rewrite the signal *pre-persist*. Runs on the hot path; implementations should be fast. Default: `[LocationCaptureTransform]`.
2. *(Framework: persist → projection → view → mode-skill → stop-dispatch.)*
3. **Broadcast** — the signal is emitted into `SignalHub`, a multicast dispatcher. `Sigil.signals: Stream[Signal]` returns a fresh subscriber per call (each with its own bounded queue; slow subscribers drop oldest with a warn, don't block peers).
4. **Per-viewer stream** — `Sigil.signalsFor(viewer): Stream[Signal]` first drops signals via `Sigil.canSee(signal, viewer)` (hard scope rule, not mutate-only — see [[MessageVisibility]] below), then folds survivors through `viewerTransforms: List[ViewerTransform]` for redaction. Different viewers can see different versions. Defaults: `canSee` reads `Event.visibility`; `viewerTransforms = [RedactLocationTransform]` strips sender-private `Message.location`. Deltas always pass `canSee`; client UIs must ignore deltas whose target event was filtered.
5. *(Framework: fan-out to agent participants via `TriggerFilter`.)*
6. **Settled effects** (`Sigil.settledEffects: List[SettledEffect]`) — post-persist side effects. Each returns `Task[Unit]`; effects decide internally whether to run sync (block publish) or spawn a fiber (fire-and-forget). Default: `[MessageIndexingEffect, GeocodingEnrichmentEffect]`.

Apps extend by overriding the list-returning methods — add, remove, or reorder. No custom DSL: `List[T]` + `T.apply(...)` is the whole contract. See `design/signal-pipeline.md` for the full rationale and level boundaries.

`SignalBroadcaster` (callback-style wire transport) has been removed. Apps that need to push to WebSocket/SSE consume `sigil.signals` or `sigil.signalsFor(viewer)` and drive the wire themselves.

**MessageVisibility** is the hard scope rule on every `Event` (default `MessageVisibility.All`):
- `All` — every viewer sees it.
- `Agents` — only viewers whose `ParticipantId` is an `AgentParticipantId`. For internal Planner/Worker/Critic chatter that mustn't reach a user UI.
- `Users` — only non-agent viewers.
- `Participants(ids)` — explicit allow-list.

Enforced at two points by `Sigil.canSee(signal, viewer)`:
- **Wire delivery** — `signalsFor(viewer)` drops signals that fail the predicate.
- **Per-agent prompt-building** — `buildContext` filters `ContextFrame`s by the running agent's id before handing the view to the curator. Frames denormalize visibility from their source event at projection time (`FrameBuilder.appendFor`), so the filter is a local check with no extra DB lookup.

`SignalTransport.replay` honors the same predicate against persisted history. Apps override `Sigil.canSee` (and/or `visibilityAllows`) for custom scope rules — per-tenant, per-permission-grant, etc.

### Providers

`sigil.provider.Provider` is a wire-agnostic trait returning `Stream[ProviderEvent]`. Implementations live under `sigil/provider/{anthropic,openai,google,deepseek,llamacpp}/`. Each carries a `ProviderType` enum case.

`Provider.models: List[Model]` reads from `Sigil.cache: ModelRegistry` — an in-memory `AtomicReference[Map[Id[Model], Model]]` populated by `OpenRouter.refreshModels` and persisted to `${dbPath}/models.json` as a fallback for offline boots. Reads are synchronous (no DB roundtrip), safe to call on hot paths like `isImageOnlyModel`. The framework auto-refreshes every `Sigil.modelRefreshInterval` (default 8 hours) on a background fiber; apps that want a different cadence (or `None` for manual-only) override the hook.

**System prompt** is rendered by `Provider.renderSystem` (`core/src/main/scala/sigil/provider/Provider.scala`). The `Current mode:` line is followed by an `Other modes available` listing populated from `Sigil.availableModes` (deduplicated `ConversationMode :: modes`) — this is what makes `change_mode` work; without the listing the model has no idea what targets exist. Apps that override `modes` automatically get them advertised.

**Grammar-constrained tool args** (per-provider dialect):
- **OpenAI Responses** — emits `"strict": true` on each function tool. The schema goes through `sigil.provider.StrictSchema(...)` first: every property becomes `required` (optionals widened to nullable `["t","null"]` or `anyOf`), `additionalProperties: false` everywhere, and the unsupported keywords (`pattern`, `format`, `minLength`/`maxLength`, numeric bounds) are stripped. Eliminates malformed-args failures (unclosed strings, degenerate token loops).
- **DeepSeek** — chat-completions wire format with the same `strict: true` + `StrictSchema(...)` treatment. DeepSeek mirrors OpenAI strict-mode semantics.
- **Google Gemini** — function calling is natively grammar-constrained (no `strict` flag). Schemas go through `StrictSchema.stripUnsupportedKeys(...)` plus `stripAdditionalProperties` (Gemini's validator rejects `additionalProperties`).
- **Anthropic** — no strict-mode equivalent; generation isn't grammar-constrained. Schemas still go through `stripUnsupportedKeys` so the API can't reject unknown keywords. Real safety net is `ToolInputValidator` (see Tools).
- **llama.cpp** — natively grammar-constrains tool args from the `parameters` schema; `stripUnsupportedKeys` for parity.

`Orchestrator.process` (`sigil/orchestrator/Orchestrator.scala`) translates `ProviderEvent` → `Signal`. It also runs `tool.execute` for atomic tool calls inline (calls where no `ContentBlockDelta` streamed — otherwise they're pre-streaming and the provider handles the content).

`OpenAICompatibleEmbeddingProvider.postJson` retries on HTTP 5xx and on bodies that contain an `error` field (3 attempts, 200ms / 800ms / 2s backoff). A single OpenAI hiccup used to abort 25-minute benchmark runs because callers tried `response("data")` on an error body and threw; the retry contains transient failures inside the provider.

### Tools

`Tool[Input <: ToolInput]` is case-class-derived: `Input derives RW` → JSON schema is generated by `DefinitionToSchema`. Core tools live in `sigil/tool/core/` (`RespondTool`, `ChangeModeTool`, `FindCapabilityTool`, `StopTool`, `NoResponseTool`). Apps register app-specific tools via a `ToolFinder` (the default is `InMemoryToolFinder`). `ConsultTool.invoke` runs a focused one-shot tool call (e.g. `TopicClassifierTool`, `ExtractMemoriesTool`) bypassing the conversation loop.

`find_capability` is the runtime discovery path — agents call it and `Sigil.findTools` returns matching `Tool`s. Discovered-but-uncalled tools decay after one turn (`decaySuggestedTools`).

**Post-decode validation.** `ToolInputValidator` walks parsed tool args alongside the input's `Definition` and re-checks every constraint (`pattern`, length, numeric bounds, array bounds) before `tool.execute` runs. The orchestrator's `ToolCallAccumulator.complete()` calls it just after `JsonParser` and just before `inputRW.write`; a violation emits a `ProviderEvent.Error` and the typed input is never produced. This closes the gap that grammar-constrained decoders (OpenAI strict mode, Gemini) open by design — those decoders strip `pattern`/`format`/numeric-bound keywords from the wire schema because character-level constraints don't compose with token-level sampling. Sigil keeps the annotations on the Scala types and re-validates here for every provider, strict or not.

### Memory

Three pathways, all writing `ContextMemory` with a `MemorySpaceId` discriminator:

1. **Critical** — app writes `source = MemorySource.Critical` via `persistMemory`. Retriever always surfaces.
2. **Compression-time** — `MemoryContextCompressor` calls `ExtractMemoriesTool` + `SummarizationTool` when the curator signals budget pressure. Target space from `compressionMemorySpace(convId)`.
3. **Per-turn** — `StandardMemoryExtractor` (wired via `Sigil.memoryExtractor`) runs after every `Done` event on a background fiber. Uses `ExtractMemoriesWithKeysTool` → `upsertMemoryByKey` (versioned).

Full write-up in `src/main/scala/sigil/conversation/compression/README.md`.

When vector search is wired (`embeddingProvider.dimensions > 0 && vectorIndex != NoOpVectorIndex`), `Sigil` auto-embeds settled Messages, persisted memories, and persisted summaries into `VectorIndex`. Point ids are name-based UUIDs derived from lightdb ids so upserts are deterministic.

### SpaceId (multi-tenancy)

`SpaceId` (in package `sigil`) is an open `PolyType` for scoping persisted resources — memories, tools, future records. Sigil ships no concrete cases. Apps define their own (UserSpace, ProjectSpace, GlobalSpace, etc.) and register them via `spaceIds: List[RW[? <: SpaceId]]`. Used by `ContextMemory.spaceId`, `Tool.spaces`, and `Sigil.accessibleSpaces(chain)`.

### Tool collection (DB-backed)

Tools are persistable records — `Tool` is a trait that extends both `RecordDocument[Tool]` and `PolyType[Tool]`. They live in `SigilDB.tools` and are queryable by indexed fields (`toolName`, `modeIds`, `spaceIds`, `keywordIndex`, `createdByIndex`).

Two authoring shapes:
- **Static singletons**: typically `case object MyTool extends TypedTool[MyInput](name = …, description = …, …)`. `TypedTool` is the authoring helper that takes input metadata as constructor args + a `ClassTag` for runtime input matching.
- **Dynamic records**: `case class ScriptTool(...) extends Tool derives RW`. Apps construct instances at runtime and persist via `Sigil.createTool(tool)`.

`Sigil.staticTools` defaults to `CoreTools.all` (the framework essentials); apps override and concatenate to add their own. The `StaticToolSyncUpgrade` (a `DatabaseUpgrade` with `alwaysRun = true, blockStartup = true`) upserts `staticTools` into the DB on every startup, prunes orphan static records (`createdBy = None` whose name isn't in the current set), and leaves user-created records (`createdBy.nonEmpty`) untouched.

Discovery — `find_capability` builds a `DiscoveryRequest` (keywords, chain, mode, accessible spaces) and hands it to `Sigil.findTools(request)` which by default is a `DbToolFinder`. Filtering uses `DiscoveryFilter.matches` which checks mode affinity (`tool.modes.contains(currentMode.id)`), space affinity (`tool.spaces` empty OR intersects `callerSpaces`), and keyword scoring across name/description/keywords. Apps override the finder for marketplace integrations or union-of-sources strategies.

Authorization — `Sigil.accessibleSpaces(chain): Task[Set[SpaceId]]` is the hook apps wire to expose the caller's authorized scope. Default `Set.empty` (fail-closed): scoped tools are hidden unless apps explicitly authorize.

User-created tools — apps' agent flows that dynamically generate tools (e.g. an LLM-generated scraper) call `sigil.createTool(MyAppTool(spaces = Set(userId), …))`. The record's polymorphic RW must be registered via `toolRegistrations: List[RW[? <: Tool]]` for round-trip.

### Mode (open PolyType)

`Mode` is a trait, not an enum. Sigil ships only `ConversationMode` (`name = "conversation"`). Apps define their own case objects (`CodingMode`, `WorkflowMode`, `WebNavigationMode`, whatever) and register them via a single list on `Sigil`:

```scala
override protected def modes: List[Mode] = List(WorkflowMode, CodingMode)
```

The framework derives both the polymorphic RW registrations (via `RW.static(_)`) and the `modeByName(name)` index from that one list. `ConversationMode` is prepended automatically; if apps accidentally list it, the registration dedupes.

A `Mode` carries:
- `name` — stable discriminator persisted in events and used by `change_mode` tool args (the tool takes a string; framework resolves via `modeByName`).
- `description` — rendered into the system prompt.
- `skill: Option[ActiveSkillSlot]` — replaces the old `modeSkill` hook; framework reads `mode.skill` directly on `ModeChange` settle.
- `tools: ToolPolicy` — tool-availability policy.

`ToolPolicy` is a sum type (`sigil.provider.ToolPolicy`) covering six composition policies:

| Case | Roster | Discovery catalog |
|---|---|---|
| `Standard` | baseline + `find_capability` | full |
| `None` | essentials only (no `find_capability`) | empty |
| `Active(names)` | baseline + names | full |
| `Discoverable(names)` | baseline | full (apps override `discoveryCatalog` for cross-mode gating) |
| `Exclusive(names)` | essentials + names (baseline suppressed) | names only |
| `Scoped(names)` | baseline | names only |

Framework essentials (always in the roster) are `respond`, `no_response`, `change_mode`, `stop`. `find_capability` joins them unless `ToolPolicy.None` is active.

Composition happens in `Sigil.effectiveToolNames(agent, behavior, mode, suggested)` and `Sigil.modeAllowsDiscovery(mode, toolName): Boolean` — both are `def`s apps override for exotic rules. The behavior + mode policies are layered in order via an internal fold: each `Active(names)` / `Exclusive(names)` contributes extras, `None` / `Exclusive` strips baseline, `None` strips `find_capability`. `Sigil.process` calls `effectiveToolNames`; `FindCapabilityTool.execute` filters its `ToolFinder` results through `modeAllowsDiscovery`.

The discovery path is deliberately predicate-based, not list-based — `ToolFinder` produces keyword-matched subsets (DB-backed finders may stream) and the framework applies the mode predicate per result. No "all tools" list ever materializes.

### Behavior (per-agent role primitive)

`Behavior` (`sigil.behavior.Behavior`) is the per-agent role assignment that lives alongside `Mode`. Mode is conversation-level and mutable (agent can swap via `change_mode`); Behavior is agent-level and immutable for the agent's lifetime — agents do not change their own behaviors.

`Behavior` is a plain case class — no PolyType registration, no trait hierarchy:

```scala
case class Behavior(name: String,
                    description: String,
                    skill: Option[ActiveSkillSlot] = None,
                    tools: ToolPolicy = ToolPolicy.Standard) derives RW
```

Apps define behaviors as values (`val PlannerBehavior = Behavior(name = "planner", description = "...", tools = ToolPolicy.Exclusive(...))`) and pass them on `AgentParticipant.behaviors: List[Behavior]`. The default is `List(GeneralistBehavior)` — vanilla agents have a real generalist role description, no empty-list fallback. An empty `behaviors` list throws at construction.

**Per-turn dispatch.** `AgentParticipant.process` is final. It iterates `behaviors` in declaration order; for each behavior it injects an `ActiveSkillSlot(name = behavior.name, content = behavior.description)` into the agent's projection (per-turn view copy, persisted view untouched) keyed under `SkillSource.Behavior(behavior.name)`, and delegates to `Sigil.process(participant, behavior, enrichedContext, triggers)`. The slot flows through the existing `aggregatedSkills` → `renderSystem` pipeline as a normal "Active skills" entry — no separate prompt section.

**App customization** lives on `Sigil.process(participant, behavior, ctx, triggers)` — apps override and dispatch on `behavior.name` to specialize per-behavior turn shapes:

```scala
override def process(participant, behavior, ctx, triggers) = behavior.name match {
  case "worker"  => runWorker(ctx, triggers)        // skips LLM, reacts to triggers
  case _         => super.process(participant, behavior, ctx, triggers)
}
```

The default `Sigil.process` delegates to `defaultProcess` which runs the standard one-round-trip LLM cycle, parameterized by the active behavior (its `tools` fold into `effectiveToolNames`).

### Geospatial (`sigil.spatial`)

`Message.location: Option[Place]` carries a `Place(point, address, name)`. Three first-class configurations:

1. **No geo** — default; framework does nothing.
2. **Raw-GPS only** — app overrides `locationFor` (or attaches `Place(point, None, None)` at the client); keeps `geocoder = NoOpGeocoder`. Messages persist with the point; no enrichment or cache writes.
3. **Full enrichment** — app wires a non-NoOp `Geocoder` (typically `CachingGeocoder(delegate, sigil, ttl)`). In `publish`, non-agent Messages with a bare point spawn a fire-and-forget task: cache lookup via `spatialContains`, delegate on miss, then `publish(LocationDelta(...))` updates the persisted Message in place.

Privacy: `location` is sender-private. The default `RedactLocationTransform` in `Sigil.viewerTransforms` strips `Message.location` for any viewer who isn't the sender — wire transports that consume `sigil.signalsFor(viewer)` get redaction for free. Projections (`ContextFrame`) never carry geo by construction.

- `locationFor` runs synchronously inside `publish` — implementations should be fast (opt-in lookup + cached GPS, not a remote call per invocation).

The Google Places HTTP client (or any concrete geocoder) lives in apps, not Sigil. The framework ships the abstractions + spatial-containment cache only.

### Transport (`sigil.transport`)

`SignalTransport(sigil)` is the bridge from `Sigil.signalsFor(viewer): Stream[Signal]` to a wire `SignalSink`. It owns subscribe → replay → forward and exposes a single `attach(viewer, sink, resume, conversations): Task[SinkHandle]`. Detaching closes the sink and stops further delivery.

**Replay is database-driven.** No in-memory buffer — `SignalTransport.replay(viewer, resume)` queries `SigilDB.events` directly. `ResumeRequest` cases:

- `None` — skip replay, attach to live only.
- `After(cursor)` — events with `timestamp.value > cursor`. Cursor is epoch-millis, used as `Last-Event-ID` (SSE) or in the resume payload (DurableSocket).
- `RecentMessages(n)` — most recent `n` `Message` events PLUS every non-Message event (ToolInvoke, ToolResults, ModeChange, TopicChange, ...) that landed after the nth-newest Message. Counts Messages, not events — chatty turns full of tool calls can't crowd Messages out of the budget.

`viewerTransforms` are applied to every replayed event (same redaction the live path applies via `signalsFor`). Replay returns Events only — Deltas are not separately persisted, so on reconnect the client receives the *settled* state of any events it missed; the live stream picks up future Deltas as normal.

Boundary de-dupe: live Events with `timestamp.value` ≤ the latest replayed timestamp are filtered out so a publish racing with replay isn't double-delivered. Deltas always pass through.

**Built-in sinks:**

- `SseFramer.sink(write, config)` — wraps a `String => Task[Unit]` write callback (typically the HTTP response body sink). Each push becomes `id: <epoch-millis>\ndata: <json>\n\n` for Events; Deltas emit only `data:` (no resume cursor). Heartbeats are not driven by the framer — apps interleave `SseFramer.Heartbeat` (`:hb\n\n`) at the HTTP layer.
- `DurableSocketSink(session)` — wraps a `spice.http.durable.DurableSession[Id, Event, Info]`. Events go through `protocol.push` (which appends to the outbound `EventLog` so they're resumable); Deltas go through `protocol.sendEphemeral` (in-flight state, not replayed).
- `SigilDbEventLog(sigil)` — a `spice.http.durable.EventLog[Id[Conversation], Event]` adapter for apps that wire a `DurableSocketServer` and want resume reads to hit `SigilDB.events` rather than spice's in-memory log. `append` is a no-op (Sigil.publish already persists); `replay` queries by conversationId + timestamp cursor.

Channels default to per-`ParticipantId` (matching `signalsFor(viewer)`) — apps that want per-conversation channels pass `conversations = Some(Set(...))` to `attach`.

`spice-server` is a core dep because of `DurableSocketSink`. Apps that don't need DurableSocket simply don't reference it; the import is unused but the runtime overhead is just the spice-server jar on the classpath.

## Conventions

- **One top-level class per file.** Enums/traits too. Companion objects may co-locate. (User memory: `feedback_one_class_per_file.md`.)
- **Scala 3 enums over strings** for fixed value sets.
- **Typed wrappers over raw strings** where there's a natural type (`Id[T]`, `URL`, `Timestamp`, enums). Don't widen a field to `String` to make a test easier.
- **Fabric annotation literals only** (`@description("...")`), no concatenation — macros expand at compile time.
- **No half-wired features.** A new type/field that isn't plumbed end-to-end is worse than nothing.
- **Don't test the framework itself.** If the only way the test fails is if Scala's stdlib broke, it's not a useful test.
- **`git mv` when renaming tracked files.** Never `mv` or Write+Delete.
- **Check test output for exceptions.** Green tests can mask background-fiber throws — grep output for `ERROR`/`Exception`/stack traces before declaring success. (Relevant because the agent loop and memory extractor run on background fibers via `startUnit()` and failures are logged but don't fail the test.)
- **WIP / design docs** live in `design/<topic>.md`, not `wip.md` in random places.

Formatter: scalafmt 3.8.6, Scala 3 dialect, `maxColumn = 140`, asterisk docstrings, dangling parens off. See `.scalafmt.conf`.

## Downstream consumers

Sigil has out-of-repo consumers (see private memory). Breaking API changes — tool dispatch, participant abstractions, event shapes — accumulate migration friction, so flag them explicitly when planning.
