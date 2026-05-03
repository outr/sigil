# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test

Scala 3.8.3 on sbt. The root project is `sigil`; sub-projects: `core` (framework), `secrets` (server-side secret store, opt-in module), `script` (REPL-backed code execution, opt-in module — adds `scala3-repl` dep), `mcp` (MCP client + persisted server registry, opt-in module), `benchmark` (memory-retrieval runners), `docs` (mdoc).

- Compile: `sbt compile`
- All tests: `sbt test`
- One suite: `sbt "testOnly spec.OrchestratorTopicSpec"`
- One test inside a suite: `sbt "testOnly spec.OrchestratorTopicSpec -- -z 'substring from test name'"`
- Live-suite gating: there is no test tag — specs that need an external service (`LlamaCpp*Spec.scala` against `llama.voidcraft.ai`, etc.) **self-skip** when the service is unreachable. Run `sbt test` everywhere; CI just relies on the same self-skip when the test runner has no network egress to external endpoints.
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

Apps extend `Sigil` and only override what they actually customize. The minimum shape is `buildDB` (concrete DB constructor) and `providerFor` (LLM provider resolver) — the only two abstract members without defaults. Most other hooks (`curate`, `getInformation`/`putInformation`, `compressionMemorySpace`, `embeddingProvider`, `vectorIndex`, `wireInterceptor`, plus the polymorphic registration lists `signalRegistrations` / `participantIds` / `spaceIds` / `participants` / `modes`) ship sensible defaults so apps that don't use the corresponding feature don't repeat the no-op. See `README.md` for the minimal shape.

**Lifecycle (two-phase).**
- `Sigil.polymorphicRegistrations: Task[Unit]` — phase-1, populates every fabric `PolyType` discriminator (Signal, ParticipantId, Mode, ToolInput, Participant, Tool, SpaceId) with framework + app-defined subtypes. Pure JVM-level effect, does NOT open the LightDB / RocksDB store. Idempotent (`.singleton`).
- `Sigil.instance: Task[SigilInstance]` — phase-2; runs `polymorphicRegistrations` first, then opens the DB, applies upgrades, wires vector index, starts the model-refresh fiber if configured. Runtime consumers (servers, REPL) call `instance` (or `withDB`, which calls it transitively).

Codegen / schema-introspection tasks (Dart generator, OpenAPI dumper) call `polymorphicRegistrations.sync()` instead of `instance.sync()` — that gives them populated `summon[RW[Signal]].definition` etc. without the RocksDB lock acquire, so codegen can run while a backend server is live.

**Shutdown.** `Sigil.shutdown: Task[Unit]` releases shared resources — closes the SignalHub (every active `signals` / `signalsFor(viewer)` subscriber's stream completes naturally — no app-side running-flag bookkeeping needed) and disposes the DB if `instance` was ever started (codegen-only paths skip the DB dispose). Idempotent. CLI / one-shot consumers call this before returning from `main` so the JVM exits cleanly without needing `System.exit`. Long-running servers don't need to call it during normal operation.

### Event-sourced conversations

- `Event` (Message, ToolInvoke, ToolResults, TopicChange, ModeChange, Stop, AgentState) is the source of truth — stored as `SigilDB.events`.
- `Signal = Event | Delta`. `Delta`s (ContentDelta, MessageDelta, ToolDelta, StateDelta, AgentStateDelta) are in-flight updates to an `Active` event; they settle it to `Complete`.
- `ConversationView` is the projection: a list of `ContextFrame`s built by `FrameBuilder` one-for-one from `Complete` events, plus per-participant `ParticipantProjection` (active skills, extra context, suggested tools). It's maintained by `Sigil.publish` idempotently and rebuildable from scratch via `Sigil.rebuildView`.
- `curate(view, modelId, chain) => TurnInput` runs on every turn. Apps decide which memories/summaries/information to surface. There is no implicit history truncation — the curator owns policy.

**Conversation management surface.** `Sigil.newConversation(...)` creates the conversation + initial Topic and (for any agent participants) fires greet-eligible behaviors via `fireGreeting`. `Sigil.addParticipant(conversationId, participant)` appends to an existing conversation's participants list, persists, and fires `fireGreeting` for newly-added agents (idempotent — re-adding an existing participant is a no-op). `Sigil.removeParticipant(conversationId, participantId)` trims the list (no farewell event today). `Sigil.deleteConversation(conversationId)` purges the conversation row plus every Event, the ConversationView, and every Topic referencing it. Both `addParticipant` and `removeParticipant` raise `ConversationNotFoundException` on a missing id. "Active conversation" stays an app concern — Sigil's `signalsFor(viewer)` emits across all conversations; apps filter to their current focus.

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

**Robustness primitives** for production deployments — all opt-in via `Provider` hooks, zero default cost:

- **`RateLimiter`** (`Provider.rateLimiter`, default `RateLimiter.NoOp`) — proactive pacing. Concrete providers feed `observe(remainingRequests, remainingTokens, resetSeconds, retryAfter)` from response headers (`x-ratelimit-remaining-*`, `retry-after`); `acquire` runs before each outgoing request and `Task.sleep`s when the bucket falls below configured floors (default `softFloor = 5%` → 250ms, `hardFloor = 1%` → 1s). `RateLimiter.forKey(apiKey)` returns a per-API-key shared limiter so two provider instances against the same upstream account share one limiter. Distinct from `ProviderStrategy`'s reactive cooldown — the strategy decides what to do AFTER a 429; the rate limiter tries to stop the 429 from happening.
- **`ErrorClassifier`** (`ProviderStrategy.errorClassifier`, default `ErrorClassifier.Default`) — categorises a thrown error as `Retry` (same candidate after `retryDelay`), `Fallthrough` (move to next candidate; current enters cooldown), or `Fatal` (stop the strategy). The default string-matches common transient signatures (rate limits, timeouts, 5xx, network errors) → `Retry`; auth errors → `Fatal`; everything else → `Fallthrough`. Apps with stronger typing (provider-specific exception types) override. Composes with `.orElse(other)` — chain a provider-specific classifier on top of `Default`.
- **`LoadBalancedProvider`** — wraps `Vector[Provider]` of equivalent pool members (e.g. multiple OpenAI accounts), round-robins requests, falls over to the next on any non-`Fatal` `ErrorClassification`. Each pool member retains its own `RateLimiter`. Distinct from `ProviderStrategy`: the strategy picks among MODELS within ONE provider tier; `LoadBalancedProvider` picks among PROVIDER INSTANCES of the same tier. The two compose — a strategy can route work to a load-balanced provider, which itself distributes across the pool.

### Tools

`Tool[Input <: ToolInput]` is case-class-derived: `Input derives RW` → JSON schema is generated by `DefinitionToSchema`. Core tools live in `sigil/tool/core/` (`RespondTool`, `ChangeModeTool`, `FindCapabilityTool`, `StopTool`, `NoResponseTool`). Apps register app-specific tools via a `ToolFinder` (the default is `InMemoryToolFinder`). `ConsultTool.invoke` runs a focused one-shot tool call (e.g. `TopicClassifierTool`, `ExtractMemoriesTool`) bypassing the conversation loop.

**Generic tools (zero new deps).** Three families ship in core for apps that want them — none are auto-registered; apps add to `staticTools` or expose via a finder:

- **`sigil.tool.fs`** — `FileSystemContext` (trait + `LocalFileSystemContext` default with optional sandbox `basePath`), plus `ReadFileTool`, `WriteFileTool`, `EditFileTool`, `DeleteFileTool`, `GlobTool`, `GrepTool`, `BashTool`. Each tool emits a `Role.Tool` Message with the result rendered as a JSON object. Remote execution wraps the tool in `ProxyTool` (don't duplicate as "client variants").
- **`sigil.tool.web`** — `WebFetchTool` (HTTP GET via spice's `HttpClient` + `HtmlToMarkdown` for HTML responses), `WebSearchTool` taking an injectable `SearchProvider` trait (apps wire Tavily / Brave / etc.; framework ships no concrete provider).
- **`sigil.tool.util`** — `SystemStatsTool` (CPU / memory / disk via shell parsing — takes a `FileSystemContext`), `SaveMemoryTool` (wraps `Sigil.persistMemory` / `upsertMemoryByKey`, takes a fixed `space: SpaceId`), `SemanticSearchTool` (wraps `Sigil.searchMemories` with chain-derived space scope). No `ask_user` or `fail` tool — those are framework-redundant: agents that need to ask a question call `respond` with `▶Text\n<question>` (or `▶Options` for a structured prompt) and yield the turn; agents that need to signal failure call `respond` with a `▶Failure` block carrying `reason` + `recoverable` for the orchestrator to pattern-match on.

`find_capability` is the runtime discovery path — agents call it and `Sigil.findTools` returns matching `Tool`s. Discovered-but-uncalled tools decay after one turn (`decaySuggestedTools`).

**Post-decode validation.** `ToolInputValidator` walks parsed tool args alongside the input's `Definition` and re-checks every constraint (`pattern`, length, numeric bounds, array bounds) before `tool.execute` runs. The orchestrator's `ToolCallAccumulator.complete()` calls it just after `JsonParser` and just before `inputRW.write`; a violation emits a `ProviderEvent.Error` and the typed input is never produced. This closes the gap that grammar-constrained decoders (OpenAI strict mode, Gemini) open by design — those decoders strip `pattern`/`format`/numeric-bound keywords from the wire schema because character-level constraints don't compose with token-level sampling. Sigil keeps the annotations on the Scala types and re-validates here for every provider, strict or not.

**Pre-execution preconditions.** `Tool.preconditions: List[ToolPrecondition]` (default `Nil`) declares gates the orchestrator runs before `tool.execute`. Each precondition returns `ToolPreconditionResult.Satisfied` (proceed) or `ToolPreconditionResult.Unsatisfied(reason, suggestedFix)` (skip execution; emit a `Role.Tool` Message with a `Failure(recoverable = true)` block describing the blocked state, optionally pointing at a setup tool). Useful for "this tool needs an active OAuth token" / "this tool needs Docker running" / "this tool requires the user has budget" — apps wire concrete checks; the orchestrator's `executeAtomic` integrates them at the gate, so the agent reads a Failure block on its next turn and (typically) calls the suggested setup tool. Preconditions are descriptive only; they identify the gap, they don't fix it.

### Content rendering

A Message's `content: Vector[ResponseContent]` is the structural reply shape — every block carries enough metadata for arbitrary projection, not just the agent's first display. `sigil.render.ContentRenderer[Output]` is the projection table that turns a vector of blocks into a target representation. Four `ContentRenderer[String]` implementations ship in core (registered on `Sigil.contentRenderers`):

| Key | Renderer | Use |
|---|---|---|
| `markdown` | `MarkdownRenderer` | CommonMark — in-app conversation UI, GitHub flavoured surfaces |
| `slack` | `SlackMrkdwnRenderer` | Slack mrkdwn dialect (single-asterisk bold, `<url\|label>` links, no images) |
| `html` | `HtmlRenderer` | Email bodies, web preview panes; HTML-escapes all text |
| `text` | `PlainTextRenderer` | SMS, voice TTS, accessibility fallbacks — no markup of its own |

Apps register additional named renderers (`"discord"`, `"teams"`, terminal ANSI, etc.) by overriding `Sigil.contentRenderers`. Apps that need non-`String` outputs (Slack Block Kit JSON AST, HTML element trees) define their own typed registry alongside — the framework's `String` registry stays as-is to keep the common "render-and-send-text" path simple.

Multi-destination routing is app-side: a `SettledEffect` consumes settled `Message` events, looks up the right renderer in `sigil.contentRenderers`, and pushes the rendered string to whatever sink the app wires (Slack `chat.postMessage`, SES, a webhook). The framework provides the rendering primitives; apps choose where the bytes land.

**Card composition.** `ResponseContent.Card(sections, title, kind)` groups other blocks into a single composable unit. Apps that want card-shaped replies opt in to the `respond_card` / `respond_cards` tools (shipped in core but NOT in the default `CoreTools.all` roster — adding them to a small default surface shifts the LLM's tool-selection decisions in ways that hurt mode-switch / reply-shape choices for apps that won't use cards). `respond_card` emits a single Card; `respond_cards` packages a sequence (dashboard tiles, search-result hits) into one Message. `sections` is stored as `List[fabric.Json]` rather than `Vector[ResponseContent]` to break a self-referential cycle in fabric's auto-derived RW (otherwise Card → Vector\[parent\] → enum RW deadlocks during lazy-val initialization). The wire format is identical to what `Vector[ResponseContent]` would have produced — no observer downstream sees the difference. Apps construct Cards from typed blocks via `Card(blocks, title, kind)` (the helper in `sigil.tool.model.Card`) and read them back via `Card.typedSections(card)`. Renderers always go through `Card.typedSections` to recover the typed structure.

### Memory

Three pathways, all writing `ContextMemory` with a `MemorySpaceId` discriminator:

1. **Critical** — app writes `source = MemorySource.Critical` via `persistMemory`. Retriever always surfaces.
2. **Compression-time** — `MemoryContextCompressor` calls `ExtractMemoriesTool` + `SummarizationTool` when the curator signals budget pressure. Target space from `compressionMemorySpace(convId)`.
3. **Per-turn** — `StandardMemoryExtractor` (wired via `Sigil.memoryExtractor`) runs after every `Done` event on a background fiber. Uses `ExtractMemoriesWithKeysTool` → `upsertMemoryByKey` (versioned).

Full write-up in `src/main/scala/sigil/conversation/compression/README.md`.

When vector search is wired (`embeddingProvider.dimensions > 0 && vectorIndex != NoOpVectorIndex`), `Sigil` auto-embeds settled Messages, persisted memories, and persisted summaries into `VectorIndex`. Point ids are name-based UUIDs derived from lightdb ids so upserts are deterministic.

**Memory-write metadata.** Beyond the core fact / source / spaceId / key tuple, `ContextMemory` carries optional governance fields apps fill in to drive lifecycle / audit / pruning:

- `confidence: Double` (default 1.0) — how sure the writer is the fact is correct. Apps surface low-confidence memories differently in retrieval ranking.
- `expiresAt: Option[Timestamp]` — TTL for retrieval. `StandardMemoryRetriever.isExpired(m, now)` skips records whose `expiresAt` is set and not in the future. The store row stays — only the per-turn surfaced set excludes it. Apps that want hard eviction (DB-level deletion) wire a separate sweep `SettledEffect`. Distinct from versioning's `validUntil` (which marks superseded versions, not TTL-evicted ones).
- `justification: Option[String]` — free-form reason the memory was recorded ("inferred from explicit user request 2026-05-02"). Audit / debugging signal; apps surface in their pinned-memory review UI.

The curator's `resolveMemoriesAndSummaries` filter expired records out of both criticals and retrieved buckets at every turn, so an expired Critical memory stops being rendered without losing the durable record.

### SpaceId (multi-tenancy)

`SpaceId` (in package `sigil`) is an open `PolyType` for scoping persisted resources — memories, tools, future records.

**Single-assignment rule.** A record gets exactly one `SpaceId`. Never `Option[SpaceId]`, never `Set[SpaceId]`. If a tool or memory needs to be visible under a different space, copy the record. Multi-space *queries* (`Sigil.findMemories(spaces: Set[SpaceId])`, `Sigil.searchMemories(...)`) DO take sets — the rule applies to assignment, not lookup.

**Framework sentinel.** Sigil ships exactly one concrete case: `case object GlobalSpace extends SpaceId`. It exists because every framework-shipped tool (`respond`, `change_mode`, `stop`, …) needs *some* `space` and "visible to everyone" is a real concept the framework owns. Auto-registered by `Sigil.instance` — apps don't list it in their `spaceIds` overrides. Apps that want fully tenanted records simply never assign `GlobalSpace` to their own data.

Apps define their own concrete spaces (UserSpace, ProjectSpace, per-conversation session spaces, etc.) and register them via `spaceIds: List[RW[? <: SpaceId]]`. Used by `ContextMemory.spaceId`, `Tool.space`, and `Sigil.accessibleSpaces(chain)`.

**Discovery filter** (`DiscoveryFilter.matches`): `tool.space == GlobalSpace || callerSpaces.contains(tool.space)`. Tools whose space is `GlobalSpace` are visible to every caller; non-global tools require the caller's `accessibleSpaces` to include the exact space.

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

### Role (per-agent identity primitive)

`Role` (`sigil.role.Role`) is the per-agent identity assignment that lives alongside `Mode`. Mode is conversation-level and mutable (agent can swap via `change_mode`); Role is agent-level and immutable for the agent's lifetime — agents do not change their own roles.

`Role` is a plain atomic case class — no PolyType registration, no trait hierarchy, no merge method:

```scala
case class Role(name: String,
                description: String,
                skill: Option[ActiveSkillSlot] = None) derives RW
```

Apps define roles as values (`val PlannerRole = Role(name = "planner", description = "Plan tasks...")`) and pass them on `AgentParticipant.roles: List[Role]`. The default is `List(GeneralistRole)` — vanilla agents have a real generalist identity, no empty-list fallback. An empty `roles` list throws at construction.

**Merged dispatch.** `AgentParticipant.process` is final and makes one `Sigil.process(participant, ctx, triggers)` call per turn — regardless of role count. Multiple roles shape *how* the agent responds (the system prompt enumerates them), not how many times it responds. A Planner+Critic agent produces ONE response per turn that weaves both perspectives, not two sequential responses.

**Prompt rendering** branches on role-list shape:
- One role: linear render (description appears as a paragraph in the system prompt).
- Multiple roles: a `"You serve the following roles:"` preamble + per-role enumeration, so the model handles multi-role identity explicitly even when each role's description was written self-contained.

Each role's optional `skill` slot flows into the "Active skills" section keyed by `SkillSource.Role(name)` (deduplicated across all sources by name).

**Tools, greeting, and dispatch overrides are agent-level**, NOT role-level:
- `AgentParticipant.tools: ToolPolicy = ToolPolicy.Standard` — one effective roster per agent, folded with the active Mode's policy via `Sigil.effectiveToolNames`.
- `AgentParticipant.greetsOnJoin: Boolean = false` — one lifecycle decision per agent. When `true`, `Sigil.newConversation` and `Sigil.addParticipant` fire `Sigil.fireGreeting(agent, conv)` automatically — the agent's standard merged dispatch runs once with an empty trigger stream, and the role descriptions / skills drive the greeting wording. No-op when `false`.
- Non-LLM "react to triggers without calling the model" shapes belong on a separate participant trait, not on `Role`.

**App customization** lives on `Sigil.process(participant, ctx, triggers)` — apps override and dispatch on participant id (or pattern-match on `agent.roles`) for custom turn shapes:

```scala
override def process(participant, ctx, triggers) = participant match {
  case agent: AgentParticipant if agent.roles.exists(_.name == "scripted") =>
    runScripted(agent, ctx, triggers)
  case _ =>
    super.process(participant, ctx, triggers)
}
```

The default `Sigil.process` delegates to `defaultProcess` which runs one merged LLM round-trip per turn (the agent's `tools` folded into `effectiveToolNames`).

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

### Remote tool execution (ProxyTool)

`sigil.tool.proxy.ProxyTool` wraps an existing `Tool` so its execution dispatches through a `ToolProxyTransport` instead of running locally. The proxy mimics the wrapped tool's surface (name, description, schema, modes, spaces, keywords, examples) — the LLM sees an identical tool. Only `execute` is rerouted: it serializes the typed input to fabric `Json` and hands it to the transport.

Apps wire remote-execution by registering proxies instead of (or alongside) the local versions:

```scala
val localScriptTool = new ExecuteScriptTool(...)        // shape carrier
val transport: ToolProxyTransport = ...                 // app-specific (DurableSocket, HTTP, gRPC, in-process channel)
val remoteScriptTool = new ProxyTool(localScriptTool, transport)
```

`ToolProxyTransport.dispatch(toolName, inputJson, context): Stream[Event]` is the entire boundary. Implementations decide wire format, routing (typically derived from the chain — "the user in this conversation"), streaming (single terminal event vs. partial-result stream), failure semantics, and timeouts. The framework does not interpret the events the transport emits — they flow into the agent's signal stream as-is, treated like any other tool result. Visibility, replay, agent re-trigger, and persistence work the same way they would for a local tool.

The framework ships only the abstract substrate. Concrete transports (DurableSocket bridge to a per-user client, HTTP roundtrip to a tool server, etc.) live in apps.

### Script execution (`sigil-script` module)

Opt-in sub-project that adds REPL-backed script execution. Pulls in `scala3-repl` — a real dep, not free; that's why it's separate.

- `ScriptExecutor` (trait) — pluggable code-execution engine.
- `ScalaScriptExecutor` — default impl wrapping `dotty.tools.repl.ScriptEngine`. Stateful session (REPL state persists across calls); thread-safe via per-engine synchronization. Strips ` ```scala ... ``` ` code fences before evaluation. **No sandboxing** — full JVM access. Apps running untrusted scripts MUST front this with isolation or run remotely via `ProxyTool` against a dedicated executor process.
- `ScriptValueHolder` — thread-local hand-off used by the executor to inject host values into the REPL's scope (synthetic `val foo = ScriptValueHolder.store.asInstanceOf[FooType]` lines).
- `ExecuteScriptTool` — `Tool` taking a `ScriptInput(code, language)`, runs through the configured executor with a `bindings: TurnContext => Map[String, Any]` provider, emits a `ScriptResult` event (`role = Role.Tool`, `visibility = MessageVisibility.Agents` by default — script chatter stays internal to the agent loop).
- `ScriptSigil` — mixin trait apps add to their `Sigil` subclass to enable persistent script-backed tooling. Exposes `scriptExecutor: ScriptExecutor` (default `ScalaScriptExecutor`) and `scriptToolSpace(chain, requested: Option[String]): Task[SpaceId]` for the single-`SpaceId` allocation policy. Auto-registers `RW[ScriptTool]` so persisted records round-trip.
- `ScriptTool` — persistable `Tool` record (`derives RW`) the agent creates at runtime. Carries `name`, `description`, `code`, `parameters: Definition` (the args schema), and the single required `space: SpaceId`. `execute` resolves the executor via `context.sigil match { case s: ScriptSigil => s.scriptExecutor }`; bindings expose `args: fabric.Json` and `context: TurnContext`.
- `CreateScriptToolTool` / `UpdateScriptToolTool` / `DeleteScriptToolTool` / `ListScriptToolsTool` — agent-facing management surface. Create + Update emit a `ToolResults` suggestion cascade (the touched tool's schema + `update_script_tool` + `delete_script_tool`) so the framework's `suggestedTools` machinery surfaces them on the next turn (one-turn decay) — the natural "build → demo → tweak" rhythm. Update / Delete authz-check that the target's `space` is `GlobalSpace` or in `Sigil.accessibleSpaces(chain)`. List filters to that same predicate.

**Space allocation patterns** apps wire via `scriptToolSpace`:

- **Always global**: leave the default. Every created tool is `GlobalSpace`; the agent's `space` hint is ignored.
- **User-scoped**: `(chain, _) => Task.pure(MyUserSpace(chain.head.value))` — derive from chain, ignore the agent's hint.
- **Caller-picks-from-accessible**: resolve the agent's `requested` string against the app's known `SpaceId` catalog, validate the resolved value is in `accessibleSpaces(chain)`, fail otherwise.

The framework single-assignment rule (one `SpaceId` per tool, never `Set` / `Option`) holds here: to surface the same script under another space, call `create_script_tool` again with that space; the framework copies, doesn't multi-attach.

Apps add the dep (`libraryDependencies += "com.outr" %% "sigil-script" % version`), mix in `ScriptSigil`, and register the management tools they want exposed. Voidcraft-style "scripts run on the user's machine" pattern: register `ProxyTool(executeScriptTool, durableSocketTransport)` server-side; client side runs `ScalaScriptExecutor` locally and answers the proxy's wire calls.

### MCP client (`sigil-mcp` module)

Opt-in sub-project that connects Sigil agents to MCP (Model Context Protocol) servers. Apps mix `McpSigil` into their Sigil class and `McpCollections` into their `SigilDB` subclass — server configurations are persisted in `db.mcpServers` so registrations survive restarts.

**Transports.** Both the spec's transports are supported:
- `McpTransport.Stdio(command, args)` — newline-delimited JSON-RPC over a child process's stdin/stdout. Most public MCP servers ship as stdio binaries.
- `McpTransport.HttpSse(url, headers)` — POST JSON-RPC requests to a URL. Server responses arrive inline; long-lived SSE consumption for server-initiated requests is the consumer's responsibility (the included `HttpSseMcpClient` handles request/response, not bidirectional streaming).

**Wire layer.** `McpJsonRpc` is the bidirectional message pump — outgoing requests, outgoing notifications, and dispatch of incoming server-initiated requests (notably `sampling/createMessage`) and notifications (`notifications/initialized`, `notifications/cancelled`, `notifications/{tools,resources,prompts}/list_changed`).

**Connection lifecycle** is owned by `McpManager`. It lazy-connects on first use (boot is independent of MCP-server reachability), idle-times-out connections (`McpServerConfig.idleTimeoutMs`, default 5 min), and refreshes cached tool/resource/prompt lists periodically (`refreshIntervalMs`, default 30 min) and on every reconnect.

**Tool surface.** `McpToolFinder` implements `ToolFinder`. Each MCP-advertised tool becomes an `McpTool` whose:
- `name` is `serverConfig.prefix + serverTool.name` (apps disambiguate cross-server collisions via the `prefix`).
- `inputDefinition` is built by `JsonSchemaToDefinition` from the server's raw JSON Schema — the LLM sees the server's schema, the agent's typed input arrives as `JsonInput(json: Json) extends ToolInput with JsonWrapper`.
- `spaces` is `serverConfig.space.toSet` — apps confine MCP servers to user / tenant / project spaces.
- `execute` calls `McpManager.callTool` and translates the server's `CallToolResult` to one or more `Message` events.

**Sampling** (`sampling/createMessage` from server): `McpSigil.samplingHandlerFor(config)` returns a per-server `SamplingHandler`. The default `ProviderSamplingHandler` (active when `samplingModelId` is set) translates the server's request into a `OneShotRequest` and runs it through `Sigil.providerFor(modelId, chain).apply(...)` — collecting `TextDelta` / `ContentBlockDelta` events into the response text. `Refusing` (when `samplingModelId` is unset) fails the request with an explanatory error. Apps override the hook for custom sampling (token budgets, model fallback, etc.).

**HTTP+SSE bidirectional flow** is real: after `initialize`, `HttpSseMcpClient` opens a long-lived SSE GET on the endpoint via spice's `streamLines()`. Each incoming `data:` line is parsed and routed through the same `McpDispatcher` that POST responses go through — so server-initiated requests (sampling, `roots/list`) and notifications (`notifications/*`) work over both transports.

**Cancellation** is wired automatically: on `Stop` events targeting an agent, `McpManager.cancelInFlight` sends `notifications/cancelled` for every in-flight MCP call owned by that agent. In-flight tracking uses the wire-level request id captured via `McpClient.callTool`'s `onWireId` callback.

**Cache invalidation on notifications**: `McpManager` injects a notification listener into every client it constructs that watches for `notifications/{tools,resources,prompts}/list_changed` and invalidates the relevant cache slice. Subsequent calls re-fetch the fresh list.

**Runtime management tools** (toggleable via `McpSigil.mcpManagementToolsEnabled`, default true): `add_mcp_server`, `list_mcp_servers`, `remove_mcp_server`, `test_mcp_server`, `refresh_mcp_server`, `read_mcp_resource`, `list_mcp_prompts`, `get_mcp_prompt`. Apps that don't want runtime mutability set the flag false.

### Metals (`sigil-metals` module)

Opt-in sub-project that owns Metals' (Scala LSP) lifecycle and surfaces Metals' MCP-exposed tools (`find-symbol`, `find-usages`, `get-docs`, `compile`, `test`, …) into the agent roster automatically. Built on top of `sigil-mcp` — Metals' MCP server is just another `McpServerConfig` once registered, so the agent discovers its tools through the standard `find_capability` flow, no Metals-specific integration code on the agent side.

Apps mix in `MetalsSigil` and override `metalsWorkspace(conversationId)` to map a conversation to the workspace path Metals should index. Everything else has sensible defaults — three lines plus the workspace mapping.

**`MetalsManager` responsibilities** (one per Sigil; lazy):
- **Spawn.** `ensureRunning(workspace)` launches Metals (via `MetalsSigil.metalsLauncher`, default `["metals"]`) under the workspace as cwd, polls `.metals/mcp.json` for the chosen MCP endpoint (`{"port": <int>}` or `{"url": "<full url>"}`), then upserts an `McpServerConfig(name = "metals-<hash-of-workspace-path>", transport = HttpSse(...), roots = [workspace])` into `db.mcpServers`. `McpManager` picks the connection up through its existing flow.
- **Port-churn handling.** A poll loop re-reads `.metals/mcp.json` on every tick; on endpoint drift (Metals restarted with a new port) the manager updates the `McpServerConfig` and calls `mcpManager.closeClient(name)` so the next tool use forces a reconnect against the fresh URL. The agent is shielded from the churn — it just sees an MCP server that re-established itself.
- **Idle reaping.** Subprocesses idle past `MetalsSigil.metalsIdleTimeoutMs` (default 15 minutes) get torn down; lazy respawn on the next `ensureRunning` call. Metals is heavyweight (~700MB-1GB RAM) so reaping idle sessions matters when an app has many conversations.
- **Shutdown.** `Sigil.onShutdown` (new hook on `Sigil`, runs before SignalHub close + DB dispose) tears down every spawned subprocess and removes the `McpServerConfig` rows. A JVM shutdown hook covers the catastrophic case (process killed without `Sigil.shutdown` running).

**Lifecycle tools** (toggleable via `MetalsSigil.metalsToolsEnabled`, default true): `start_metals`, `stop_metals`, `metals_status`. These are lifecycle controls only — Metals' own MCP tools flow through `McpManager` automatically once the `McpServerConfig` is registered.

**Why not bake into `sigil-tooling`:** considered, rejected. `sigil-tooling` is protocol-direct LSP/BSP integration (the agent talks LSP4J / bsp4j directly, no MCP indirection). Folding in would mix two distinct concerns — low-latency raw LSP wrapping vs. spawn-and-proxy of an MCP-exposed tool. Separate module keeps both clean.

**Why not put it in the consumer (Sage) directly:** centralising in Sigil means every Sigil consumer with Scala work gets it for free. The Metals lifecycle / `.metals/mcp.json` discovery / `mcpManager.closeClient` integration is generic — there's nothing app-specific. Consumers diverge only on `metalsWorkspace`, which is exactly what the hook is for.

### Workflow engine (`sigil-workflow` module)

Strider-backed workflow runtime — scheduled / event-triggered / one-shot multi-step programs that the framework executes on the user's behalf. Apps mix in `WorkflowSigil` and (optionally) override `workflowToolsEnabled`, `workflowBuilderModeEnabled`, `maxConcurrentWorkflows`, `workflowDbDirectory`. The lazy `workflowManager` boots the engine on first access; `Sigil.shutdown` disposes it via `WorkflowSigil.onShutdown`.

**Step types (7 framework shapes, all wired):** `JobStepInput` (LLM prompt or named tool call with `{{var}}` substitution + retry), `ConditionStepInput` (expression-eval branching), `ApprovalStepInput` (human gate; emits `WorkflowApprovalRequested` Notice; configurable timeout action), `ParallelStepInput` (fork N branches; join all-or-any), `LoopStepInput` (iterate body over a list-typed variable), `SubWorkflowStepInput` (invoke another template inline with overridden variables), `TriggerStepInput` (pause until external event fires; `same-workflow` resume vs. `branch-per-fire`).

**Trigger types (4 framework + extensible):** `ConversationMessageTrigger` (matches sender + substring), `TimeTrigger` (5-field cron or fixed-interval ms), `WebhookTrigger` (HTTP listener with `X-Webhook-Secret`), `WorkflowEventTrigger` (cross-workflow signaling via `publishEvent(name, json)`). Apps register their own subclasses (Slack, email, Git, …) via `triggerRegistrations`.

**Lifecycle events:** `WorkflowRunStarted`, `WorkflowStepCompleted`, `WorkflowRunCompleted`, `WorkflowRunFailed` are framework `Event` subtypes — they flow through the standard `Sigil.publish` pipeline (persist → projection → broadcast → settled effects), so apps observe them via `signals` / `signalsFor(viewer)` like any other event. Approval prompts ride a separate `WorkflowApprovalRequested` Notice (ephemeral; client resumes via the `resume_workflow` tool).

**Persistence:** templates live in `SigilDB.workflowTemplates` (Lucene-indexed by name / space / creator / enabled / conversation); Strider's run-state lives in a separate LightDB at `${dbPath}/workflows`. Failure-resilient — runs survive process restart.

**Authoring path:** `WorkflowBuilderMode` activates `WorkflowBuilderSkill` (in-prompt guide on step shapes, variable substitution, trigger types, the authoring loop) and adds the management tools to the agent roster: `create_workflow`, `update_workflow`, `delete_workflow`, `list_workflows`, `get_workflow`, `run_workflow`, `cancel_workflow`, `resume_workflow`, `register_trigger`, `unregister_trigger`, `list_triggers`. Apps that want a fixed catalog set `workflowToolsEnabled = false` and pre-populate `db.workflowTemplates` themselves.

### Context-limit enforcement

The framework guarantees no `HTTP 400 — context too long` from the model reaches the consumer. Layered defence:

1. **Prevention — write-time core-context cap** (`Sigil.coreContextShareLimit`, default 50%). Pinning a `MemorySource.Critical` memory that would push the inviolable share past `0.5 × model.contextLength` fails with `CoreContextOverflowException` carrying the largest existing pinned items. The cap guarantees the auto-shedding machinery always has at least 50% of the window for sheddable content. Apps tighten / loosen via the override. `Sigil.modeSkillShareLimit` (default 10%) does the same for Mode-bundled skills at startup.

2. **Auto-shedding — `StandardContextCurator.budgetResolve`**, in order: drop non-critical retrieved memories → drop unreferenced Information records → frame compression via `MemoryContextCompressor`. Critical memories are NEVER touched. The order was set by Phase 0 measurements (`benchmark/profiles/SUMMARY.md`).

3. **Provider pre-flight gate** (`Provider.preFlightGate`). After `translate`, the gate estimates the rendered request via `Provider.tokenizer` (jtokkit cl100k for OpenAI/Anthropic, heuristic fallback elsewhere) and compares to the model's `contextLength`. If over: emergency-shed (drop tool roster down to framework essentials → drop oldest frames). If still over after all shedding: `RequestOverBudgetException`.

4. **Visibility — `WireRequestProfile`** Notice (`Sigil.profileWireRequests`, default ON). Every wire request emits a per-section breakdown plus `ContextManagementInsight`s (memory share, tool roster size, compression imminent, budget near limit). Apps subscribe to `signals` filtered to `WireRequestProfile` and render their always-visible context-utilisation gauge.

5. **In-conversation warning**. When this turn's resolved Critical memories occupy more than `criticalShareWarningThreshold` (default 30%) of the model's window, the curator appends a `_budgetWarning` entry to `TurnInput.extraContext`. The agent reads it on its next turn — it can mention to the user, ignore, or call the introspection tools proactively. Stateless re-derivation per turn — no throttling state needed.

6. **Agent introspection** — three core tools handle "user wants to review pinned items":
   - `list_pinned_memories` — every Critical memory the chain can access, with key + summary + token cost.
   - `unpin_memory(key)` — demotes a memory's `source` from Critical to Compression. Doesn't delete; just removes the every-turn render.
   - `context_breakdown` — current turn's section breakdown, returned as JSON the agent can render to the user.

7. **`summary || fact` rendering**. `Provider.renderSystem` prefers `m.summary` when set, falls back to `m.fact`. Apps writing concise critical directives via the summary field shrink per-turn rendered cost; the full fact stays recoverable via `lookup(capabilityType="Memory", name=key)`.

**Durability story** (matters for shed-aggressiveness): events are append-only on `SigilDB.events`; compression / curation only edits the per-turn `TurnInput`, never the durable log. Anything trimmed from a turn's prompt is recoverable on demand via `search_conversation` (Lucene-backed event-log search), `lookup` (Memory + Information by key), or `recall_memory` (semantic). Shedding is lossy at the prompt level only.

**Phase 0 instrumentation**: `RequestProfiler` + the bench scenarios under `benchmark/src/main/scala/bench/contextprofile/` produce per-section reports in `benchmark/profiles/`. Run when iterating on shed policy or evaluating downstream apps' context shape.

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
