# sigil
[![CI](https://github.com/outr/sigil/actions/workflows/ci.yml/badge.svg)](https://github.com/outr/sigil/actions/workflows/ci.yml)
[![Lines of code](https://aschey.tech/tokei/github/outr/sigil)](https://github.com/outr/sigil)
[![Scala](https://img.shields.io/badge/scala-3.8.3-red)](https://www.scala-lang.org)
[![Scala Steward](https://img.shields.io/badge/Scala_Steward-helping-blue)](https://scala-steward.org)
[![License](https://img.shields.io/github/license/outr/sigil)](https://github.com/outr/sigil/blob/master/LICENSE)

A Scala 3 framework for building multi-agent LLM conversations with tool use, capability discovery, and persistent context.

## Features

- **Provider-agnostic** — Anthropic, OpenAI (chat-completions + Responses), DeepSeek, Google Gemini, local llama.cpp, plus app-defined; routed `ProviderStrategy` chains with per-mode pinning, per-space assignment, and cooldown-aware fallback
- **Event-sourced conversations** — `Event`s persisted via LightDB; `ConversationView` is a derived projection produced by a user-supplied curator with no implicit truncation
- **Strongly-typed tools** — `Tool[Input]` with JSON schema auto-derived via fabric `derives RW`; grammar-constrained tool args per provider plus post-decode `ToolInputValidator` re-checking every constraint
- **Capability discovery** — `find_capability` searches a persisted tool collection (queryable by mode / space / keyword) at runtime; static + user-created tools share one store
- **Multi-agent fan-out** — `Participant`s on `Conversation`, dispatched statelessly via DB-atomic `AgentState` locks; `Role` (per-agent identity) + `Mode` (per-conversation tool policy) shape behavior
- **Streaming signals** — `Event` + `Delta` as `Stream[Signal]` with per-viewer redaction (`viewerTransforms`) and visibility scopes (`MessageVisibility`) on every event
- **Database-driven transport** — `SignalTransport` bridges `signalsFor(viewer)` to SSE / DurableSocket sinks with replay from `SigilDB.events`; no in-memory buffer, `RecentMessages(n)` resume preserves tool / mode events between messages
- **Three-tier memory** — critical (app-driven), compression-time (curator pressure), and per-turn extraction; auto-embedded into a vector index when wired
- **Multi-tenancy via `SpaceId`** — open `PolyType` scoping memories / tools / future records; one space per record, multi-space queries, per-call authz via `accessibleSpaces`
- **Spatial primitives** — `Place` on messages, pluggable `Geocoder`, sender-private redaction
- **Generic tool families** — filesystem (read / write / edit / glob / grep / bash), web (fetch + injectable search), util (system stats, save memory, semantic search), `ProxyTool` for remote dispatch
- **Pluggable storage** — RocksDB + Lucene by default; opt into PostgreSQL for production via `sigil.postgres.jdbcUrl`

## Optional modules

- **`sigil-mcp`** — MCP client with stdio + HTTP+SSE transports, persisted server registry, sampling handler, automatic cancellation wiring
- **`sigil-tooling`** — LSP / BSP clients (lsp4j + bsp4j) for IDE-grade structural integration; long-lived language-server and build-server sessions, with exhaustive coverage of edit, navigation, and build-system RPCs
- **`sigil-debug`** — DAP (Debug Adapter Protocol) client for interactive debugging — launch / attach, breakpoints, step / continue / pause, stack trace, scope / variable inspection, REPL evaluation; per-language adapter configs (debugpy, lldb-dap, delve, sbt-debug-bridge, …)
- **`sigil-script`** — REPL-backed `ScalaScriptExecutor` + DB-persisted `ScriptTool`s for runtime-defined capabilities
- **`sigil-browser`** — headless browser automation via RoboBrowser
- **`sigil-secrets`** — encrypted secret store (scalapass-backed); referenced by provider configs

## SBT Configuration

```scala
libraryDependencies += "com.outr" %% "sigil-core" % "1.0.0-SNAPSHOT"

// Optional modules — add only what you need:
libraryDependencies ++= Seq(
  "com.outr" %% "sigil-mcp"     % "1.0.0-SNAPSHOT",
  "com.outr" %% "sigil-tooling" % "1.0.0-SNAPSHOT",
  "com.outr" %% "sigil-debug"   % "1.0.0-SNAPSHOT",
  "com.outr" %% "sigil-script"  % "1.0.0-SNAPSHOT",
  "com.outr" %% "sigil-browser" % "1.0.0-SNAPSHOT",
  "com.outr" %% "sigil-secrets" % "1.0.0-SNAPSHOT"
)
```

## Getting Started

Sigil is centered on a `Sigil` trait that your application extends to wire in a
provider registry, a tool finder, and (optionally) participant / memory-space
polymorphic registrations. A minimal instance looks like this:

```scala
import lightdb.id.Id
import lightdb.store.CollectionManager
import lightdb.upgrade.DatabaseUpgrade
import rapid.Task
import sigil.Sigil
import sigil.db.{DefaultSigilDB, Model, SigilDB}
import sigil.participant.ParticipantId
import sigil.provider.Provider

object MySigil extends Sigil {
  type DB = SigilDB

  override protected def buildDB(directory: Option[java.nio.file.Path],
                                 storeManager: CollectionManager,
                                 appUpgrades: List[DatabaseUpgrade]): DB =
    new DefaultSigilDB(directory, storeManager, appUpgrades)

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    Task.error(new RuntimeException("configure a provider registry"))
}
```

## Design Notes

More in-depth design documents live under the `docs/design` directory.
