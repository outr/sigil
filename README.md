# sigil
[![CI](https://github.com/outr/sigil/actions/workflows/ci.yml/badge.svg)](https://github.com/outr/sigil/actions/workflows/ci.yml)
[![Lines of code](https://aschey.tech/tokei/github/outr/sigil)](https://github.com/outr/sigil)
[![Scala](https://img.shields.io/badge/scala-3.8.3-red)](https://www.scala-lang.org)
[![Scala Steward](https://img.shields.io/badge/Scala_Steward-helping-blue)](https://scala-steward.org)
[![License](https://img.shields.io/github/license/outr/sigil)](https://github.com/outr/sigil/blob/master/LICENSE)

A Scala 3 framework for building multi-agent LLM conversations with tool use, capability discovery, and persistent context.

## Features

- **Provider-agnostic** — pluggable `Provider` abstraction over Anthropic, OpenAI, and local llama.cpp; add your own with a single trait
- **Event-sourced conversations** — `Event`s are the source of truth, persisted through LightDB; `ConversationView` is a derived projection produced by a user-supplied curator
- **Strongly-typed tools** — `Tool[Input]` with JSON schema auto-derived from case classes via fabric `derives RW`
- **Capability discovery** — agents invoke `find_capability` to search tools, skills, MCP servers, and other agents at runtime
- **Multi-agent fan-out** — `Participant`s live on `Conversation`, dispatched statelessly via DB-atomic `AgentState` locks; self-loop and @-mention delegation fall out naturally
- **Streaming signals** — `Event` + `Delta` flow as `Stream[Signal]`; consume `sigil.signals` or `sigil.signalsFor(viewer)` to drive any wire transport (Slack, WebSocket, SSE, logs…)
- **Scoped memory** — `ContextMemory` persisted with a `MemorySpaceId` discriminator, so apps can layer whatever memory spaces they need (global, per-user, per-project, etc.)
- **Mode system** — conversations carry a `Mode` (Conversation, Coding, …) that tool calls can switch mid-turn to re-select skills and instructions
- **Pluggable storage** — ships with on-disk RocksDB + Lucene by default; opt into PostgreSQL via `SIGIL_POSTGRES_JDBC_URL` for production deployments
- **Built-in caching** — per-store LRU/unbounded caches on hot reads (models, conversations, memories)

## SBT Configuration

```scala
libraryDependencies += "com.outr" %% "sigil" % "1.0.0-SNAPSHOT"
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
