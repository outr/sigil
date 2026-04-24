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

- Default host: `http://localhost:8081`
- Override: Profig key `sigil.llamacpp.host` or env `SIGIL_LLAMACPP_HOST`
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
4. **Per-viewer stream** — `Sigil.signalsFor(viewer): Stream[Signal]` is `signals.map(applyViewerTransforms(_, viewer))`. `viewerTransforms: List[ViewerTransform]` runs once per (signal, viewer) pair — different viewers can see different versions. Default: `[RedactLocationTransform]` strips sender-private `Message.location`.
5. *(Framework: fan-out to agent participants via `TriggerFilter`.)*
6. **Settled effects** (`Sigil.settledEffects: List[SettledEffect]`) — post-persist side effects. Each returns `Task[Unit]`; effects decide internally whether to run sync (block publish) or spawn a fiber (fire-and-forget). Default: `[MessageIndexingEffect, GeocodingEnrichmentEffect]`.

Apps extend by overriding the list-returning methods — add, remove, or reorder. No custom DSL: `List[T]` + `T.apply(...)` is the whole contract. See `design/signal-pipeline.md` for the full rationale and level boundaries.

`SignalBroadcaster` (callback-style wire transport) has been removed. Apps that need to push to WebSocket/SSE consume `sigil.signals` or `sigil.signalsFor(viewer)` and drive the wire themselves.

### Providers

`sigil.provider.Provider` is a wire-agnostic trait returning `Stream[ProviderEvent]`. Implementations live under `sigil/provider/{anthropic,openai,google,deepseek,llamacpp}/`. Each carries a `ProviderType` enum case.

`Orchestrator.process` (`sigil/orchestrator/Orchestrator.scala`) translates `ProviderEvent` → `Signal`. It also runs `tool.execute` for atomic tool calls inline (calls where no `ContentBlockDelta` streamed — otherwise they're pre-streaming and the provider handles the content).

### Tools

`Tool[Input <: ToolInput]` is case-class-derived: `Input derives RW` → JSON schema is generated by `DefinitionToSchema`. Core tools live in `sigil/tool/core/` (`RespondTool`, `ChangeModeTool`, `FindCapabilityTool`, `StopTool`, `NoResponseTool`). Apps register app-specific tools via a `ToolFinder` (the default is `InMemoryToolFinder`). `ConsultTool.invoke` runs a focused one-shot tool call (e.g. `TopicClassifierTool`, `ExtractMemoriesTool`) bypassing the conversation loop.

`find_capability` is the runtime discovery path — agents call it and `Sigil.findTools` returns matching `Tool`s. Discovered-but-uncalled tools decay after one turn (`decaySuggestedTools`).

### Memory

Three pathways, all writing `ContextMemory` with a `MemorySpaceId` discriminator:

1. **Critical** — app writes `source = MemorySource.Critical` via `persistMemory`. Retriever always surfaces.
2. **Compression-time** — `MemoryContextCompressor` calls `ExtractMemoriesTool` + `SummarizationTool` when the curator signals budget pressure. Target space from `compressionMemorySpace(convId)`.
3. **Per-turn** — `StandardMemoryExtractor` (wired via `Sigil.memoryExtractor`) runs after every `Done` event on a background fiber. Uses `ExtractMemoriesWithKeysTool` → `upsertMemoryByKey` (versioned).

Full write-up in `src/main/scala/sigil/conversation/compression/README.md`.

When vector search is wired (`embeddingProvider.dimensions > 0 && vectorIndex != NoOpVectorIndex`), `Sigil` auto-embeds settled Messages, persisted memories, and persisted summaries into `VectorIndex`. Point ids are name-based UUIDs derived from lightdb ids so upserts are deterministic.

### MemorySpaceId

`MemorySpaceId` is an open `PolyType` — Sigil does NOT ship concrete cases. Apps define their own (GlobalSpace, UserSpace, ProjectSpace, etc.) and register them via `memorySpaceIds: List[RW[? <: MemorySpaceId]]`. See project memory `project_sigil_memory_spaceid.md`.

### Geospatial (`sigil.spatial`)

`Message.location: Option[Place]` carries a `Place(point, address, name)`. Three first-class configurations:

1. **No geo** — default; framework does nothing.
2. **Raw-GPS only** — app overrides `locationFor` (or attaches `Place(point, None, None)` at the client); keeps `geocoder = NoOpGeocoder`. Messages persist with the point; no enrichment or cache writes.
3. **Full enrichment** — app wires a non-NoOp `Geocoder` (typically `CachingGeocoder(delegate, sigil, ttl)`). In `publish`, non-agent Messages with a bare point spawn a fire-and-forget task: cache lookup via `spatialContains`, delegate on miss, then `publish(LocationDelta(...))` updates the persisted Message in place.

Privacy: `location` is sender-private. The default `RedactLocationTransform` in `Sigil.viewerTransforms` strips `Message.location` for any viewer who isn't the sender — wire transports that consume `sigil.signalsFor(viewer)` get redaction for free. Projections (`ContextFrame`) never carry geo by construction.

- `locationFor` runs synchronously inside `publish` — implementations should be fast (opt-in lookup + cached GPS, not a remote call per invocation).

The Google Places HTTP client (or any concrete geocoder) lives in apps, not Sigil. The framework ships the abstractions + spatial-containment cache only.

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
