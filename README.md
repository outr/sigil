# sigil
[![CI](https://github.com/outr/sigil/actions/workflows/ci.yml/badge.svg)](https://github.com/outr/sigil/actions/workflows/ci.yml)
[![Lines of code](https://aschey.tech/tokei/github/outr/sigil)](https://github.com/outr/sigil)

A Scala 3 framework for building multi-agent LLM conversations with tool use, capability discovery, and persistent context.

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
import rapid.Task
import sigil.Sigil
import sigil.conversation.ConversationContext
import sigil.db.Model
import sigil.participant.ParticipantId
import sigil.provider.Provider
import sigil.tool.{InMemoryToolFinder, ToolFinder}
import sigil.tool.core.CoreTools

object MySigil extends Sigil {
  override val findTools: ToolFinder =
    InMemoryToolFinder(CoreTools(this).all.toList)

  override def curate(ctx: ConversationContext): Task[ConversationContext] =
    Task.pure(ctx)

  override def providerFor(modelId: Id[Model], chain: List[ParticipantId]): Task[Provider] =
    Task.error(new RuntimeException("configure a provider registry"))
}
```

## Design Notes

More in-depth design documents live under the `docs/design` directory.
