# Bugs

Tracking bugs found in Sigil, Spice, Fabric, and related libraries while building a downstream consumer on top of Sigil. Each entry: where it lives, what's wrong, what we observed, suggested fix.

**Status legend:** ⚠️ workaround in place · ❌ open · ✅ fixed.

Numbering preserves history — gaps reflect entries that were filed, fixed, and pruned.

---

## #20 — Ship a `WsServer` factory so consumers stop hand-rolling `DurableSocketServer + SessionBridge.attach` glue ❌

**Location:** New file `core/src/main/scala/sigil/transport/WsServer.scala`. Pulls together what's currently spread across `spice.http.durable.DurableSocketServer`, `sigil.transport.SessionBridge`, `sigil.db.SigilDB.events` (for the event log), and `spice.http.server.MutableHttpServer`.

**What's missing today:** every consumer that wires Sigil over WebSocket reinvents the same ~50 lines:

```scala
val durableServer = new DurableSocketServer[Id[Conversation], Event, Info](
  config = DurableSocketConfig(ackBatchDelay = 50.millis, ...),
  eventLog = sigil.eventLog,
  resolveChannel = (_, info) => Task.pure(Conversation.id(info.conversationId))
)
val httpServer = new MutableHttpServer
httpServer.config.clearListeners().addListeners(HttpServerListener(host = "127.0.0.1", port = Some(port)))
httpServer.handler(List(path"/ws" / durableServer))
durableServer.onSession.attach { session =>
  SessionBridge.attach(sigil, session, viewer, onSessionStart = ensureConv)
    .handleError(t => Task(session.protocol.close()).flatMap(_ => Task.error(t)))
    .start()
  ()
}
```

This is currently `SageServer.scala` minus a few lines of business logic. The handful of decisions buried in there (close-on-attach-failure, default ackBatchDelay, channel resolution from `info.conversationId`) are exactly what consumers should *not* be making themselves. They should be the Sigil-blessed default, with overrides for the rare cases.

**Suggested fix:**

```scala
package sigil.transport

import lightdb.id.Id
import rapid.Task
import sigil.Sigil
import sigil.conversation.Conversation
import sigil.event.Event
import sigil.participant.ParticipantId
import spice.http.durable.{DurableSocketConfig, DurableSocketServer, ReconnectStrategy}
import spice.http.server.MutableHttpServer
import spice.http.server.config.HttpServerListener
import spice.http.server.dsl.*
import spice.net.path

import scala.concurrent.duration.*
import scala.language.implicitConversions

/**
 * Standard WebSocket front-door for a Sigil instance. Wires:
 *   - a `DurableSocketServer` keyed by `Conversation.id(info.conversationId)`
 *   - a per-session `SessionBridge.attach` with close-on-attach-failure
 *   - an HTTP server bound to the configured listener mounting `/ws`
 *
 * Apps that want a non-default `Info` shape, channel resolver, or path
 * override the relevant constructor params; everything else stays the
 * documented default.
 */
final class WsServer[Info: fabric.rw.RW](
  sigil: Sigil,
  viewer: ParticipantId,
  port: Int,
  host: String = "127.0.0.1",
  path: String = "/ws",
  resolveChannel: (String, Info) => Task[Id[Conversation]] = WsServer.defaultResolveChannel[Info],
  onSessionStart: Id[Conversation] => Task[Unit] = (_: Id[Conversation]) => Task.unit,
  config: DurableSocketConfig = DurableSocketConfig(
    ackBatchDelay = 50.millis,
    reconnectStrategy = ReconnectStrategy.none
  )
) {
  val durableServer: DurableSocketServer[Id[Conversation], Event, Info] = ...
  val httpServer: MutableHttpServer = ...

  def start(): Task[Unit] = ...
  def stop(): Task[Unit] = ...
}

object WsServer {
  /** Default: pull `conversationId: String` off the Info via reflection-free
    * pattern — apps that don't have one (or have a different field name)
    * pass an explicit resolver. */
  def defaultResolveChannel[Info](implicit ev: Info <:< { def conversationId: String }): (String, Info) => Task[Id[Conversation]] =
    (_, info) => Task.pure(Conversation.id(info.conversationId))
}
```

The structural type bound is more precious than it looks — but the alternative (a `WsInfo` trait every Info implements) is also fine. Either way, `SageServer.scala` becomes:

```scala
final class SageServer(port: Int, defaultModelId: Id[Model]) {
  val ws = new WsServer[SageInfo](
    sigil = Sage,
    viewer = SageUser,
    port = port,
    onSessionStart = ensureConversation
  )
  def start(): Task[Unit] = ws.start()
  def stop(): Task[Unit] = ws.stop()

  private def ensureConversation(convId: Id[Conversation]): Task[Unit] = ...
}
```

**Why upstream:** The hand-rolled glue is identical across every Sigil-on-WS consumer — the right defaults (close-on-attach-failure, ackBatchDelay, channel resolution shape) want to live next to `SessionBridge`. Apps shouldn't be making the close-on-attach-failure decision themselves; that's exactly the kind of thing #16 fixed at the `SessionBridge` layer and (1) inherits at the `WsServer` layer.

**Tradeoff:** Factories tend to grow until consumers want a non-trivial override and have to drop back to manual wiring. The risk is mitigated by exposing `durableServer` and `httpServer` as `val`s on the factory so consumers can reach in for tweaks without abandoning the wrapper. **Impact:** every WebSocket consumer drops ~50 lines and gets the framework-blessed lifecycle posture by default.

---

## #21 — `Sigil.getOrCreateConversation(id, label, summary, participants, createdBy)` helper ❌

**Location:** New method on `sigil.Sigil`, alongside `newConversation`.

**What's missing today:** every chat-shaped Sigil app reinvents this exact pattern:

```scala
private def ensureConversation(convId: Id[Conversation]): Task[Unit] =
  sigil.withDB(_.conversations.transaction(_.get(convId))).flatMap {
    case Some(_) => Task.unit
    case None =>
      sigil.newConversation(
        createdBy = ...,
        label = ...,
        summary = ...,
        participants = List(...),
        conversationId = convId
      ).map(_ => ())
  }
```

It's the lazy-create-on-first-contact path that anything wiring Sigil to a UI needs — Sage has it, any future Sigil chat consumer will too. Calling `newConversation` blindly when the row already exists silently overwrites; calling `withDB(...).get` directly without a fallback is what produced the empty-conversations-list bug we hit earlier.

**Suggested fix:**

```scala
/** Get the conversation by id, or create it with the supplied
  * defaults if it doesn't exist. Returns the resulting Conversation
  * either way. The participant list and metadata are only used on the
  * create path — pre-existing conversations are returned unchanged.
  *
  * Idempotent — calling this on every WebSocket connect is the
  * canonical pattern for chat-shaped consumers.
  */
def getOrCreateConversation(
  conversationId: Id[Conversation],
  createdBy:     ParticipantId,
  label:         String          = "Conversation",
  summary:       String          = "",
  participants:  List[Participant] = Nil
): Task[Conversation] =
  withDB(_.conversations.transaction(_.get(conversationId))).flatMap {
    case Some(c) => Task.pure(c)
    case None =>
      newConversation(
        createdBy      = createdBy,
        label          = label,
        summary        = summary,
        participants   = participants,
        conversationId = conversationId
      )
  }
```

Then `SageServer.ensureConversation` collapses to:

```scala
private def ensureConversation(convId: Id[Conversation]): Task[Unit] =
  Sage.getOrCreateConversation(
    conversationId = convId,
    createdBy      = SageUser,
    label          = "Sage",
    summary        = "Sage agentic-coding chat",
    participants   = List(defaultAgent)
  ).unit
```

And it can plug straight into the new `WsServer.onSessionStart`.

**Why upstream:** ten lines that every consumer pays, all of which live on `Sigil` already. The greet-on-join fires correctly via `newConversation`, which is the whole reason Sage hand-rolls this rather than calling `withDB(_.conversations.transaction(_.upsert(...)))` directly. Putting it on `Sigil` makes the right default discoverable.

**Tradeoff:** Apps that need different `Conversation` defaults per-channel (e.g., per-user vs. per-team) end up overriding back to the manual pattern. Default values on the helper are sized for the simplest case; overrides cover the rest. Could be argued either way whether `participants` should default to `Nil` (test-shaped) or to a sensible "the agent that owns this app" (more useful but Sage-specific). The helper as proposed leaves that to the caller.

---

## #22 — `sigil.tool.AllShippedTools(fsContext): List[Tool]` helper ❌

**Location:** New file `core/src/main/scala/sigil/tool/AllShippedTools.scala`, alongside `core/CoreTools.scala`.

**What's missing today:** Sage's `Sage.staticTools` is a verbatim list of every tool Sigil ships, hand-imported and hand-instantiated:

```scala
override def staticTools: List[Tool] =
  super.staticTools ++ List(
    ConsultTool, ExtractMemoriesTool, ExtractMemoriesWithKeysTool, RerankTool, SummarizationTool,
    ForgetTool, MemoryHistoryTool, RecallTool, RememberTool,
    LookupInformationTool, new SaveMemoryTool(SageSpace), SearchConversationTool,
    SemanticSearchTool, SleepTool, new SystemStatsTool(fsContext),
    new BashTool(fsContext), new DeleteFileTool(fsContext), new EditFileTool(fsContext),
    new GlobTool(fsContext), new GrepTool(fsContext), new ReadFileTool(fsContext),
    new WriteFileTool(fsContext), new WebFetchTool()
  )
```

That's ~30 lines and 24 imports. Every consumer that wants "all the framework-shipped tools" reinvents this list verbatim. Worse, when Sigil adds a new tool, every consumer has to remember to add it manually.

**Suggested fix:**

```scala
package sigil.tool

import sigil.SpaceId
import sigil.tool.consult.*
import sigil.tool.core.CoreTools
import sigil.tool.fs.{BashTool, DeleteFileTool, EditFileTool, FileSystemContext,
                     GlobTool, GrepTool, ReadFileTool, WriteFileTool}
import sigil.tool.memory.*
import sigil.tool.util.*
import sigil.tool.web.WebFetchTool

import scala.concurrent.duration.*

/**
 * Every tool Sigil ships out-of-the-box, instantiated with reasonable
 * defaults. Drop-in for `Sigil.staticTools` overrides:
 *
 * {{{
 *   override def staticTools: List[Tool] =
 *     super.staticTools ++ AllShippedTools(LocalFileSystemContext(), space = MySpace)
 * }}}
 *
 * Excluded by default — these need consumer-supplied configuration:
 *   - `WebSearchTool` (needs a `SearchProvider`, typically API-keyed)
 *   - `TopicClassifierTool` (constructed per-call with prior labels)
 *   - `ProxyTool` (a wrapper around another tool, not a standalone)
 *
 * Apps wanting to opt OUT of any individual tool filter the result:
 * `AllShippedTools(fs).filterNot(_.name == ToolName("bash"))`.
 */
object AllShippedTools {
  def apply(
    fs: FileSystemContext,
    space: SpaceId,
    webFetchTimeout: FiniteDuration = 30.seconds
  ): List[Tool] = List(
    // Already in CoreTools.all — call sites typically chain
    // `super.staticTools ++ AllShippedTools(...)` so core isn't
    // duplicated, but we include it here for "I want everything".
    // Apps that want only the non-core extras call `extras(...)` instead.
    ConsultTool, ExtractMemoriesTool, ExtractMemoriesWithKeysTool, RerankTool, SummarizationTool,
    ForgetTool, MemoryHistoryTool, RecallTool, RememberTool,
    LookupInformationTool, new SaveMemoryTool(space), SearchConversationTool,
    SemanticSearchTool, SleepTool, new SystemStatsTool(fs),
    new BashTool(fs), new DeleteFileTool(fs), new EditFileTool(fs),
    new GlobTool(fs), new GrepTool(fs), new ReadFileTool(fs), new WriteFileTool(fs),
    new WebFetchTool(webFetchTimeout)
  )

  /** Just the non-core extras. Pair with `super.staticTools` to keep
    * `CoreTools.all` from being listed twice. */
  def extras(fs: FileSystemContext, space: SpaceId,
             webFetchTimeout: FiniteDuration = 30.seconds): List[Tool] =
    apply(fs, space, webFetchTimeout)
}
```

Then `Sage.staticTools` is:

```scala
override def staticTools: List[Tool] =
  super.staticTools ++ AllShippedTools.extras(LocalFileSystemContext(), SageSpace)
```

**Why upstream:** When Sigil adds a new tool (or, more importantly, deprecates one), this list is the right place to keep up to date — any consumer using the helper picks up the change for free. The exclusion comments document *why* `WebSearch` / `TopicClassifier` / `Proxy` aren't included so apps don't have to rediscover that the hard way.

**Tradeoff:** Opt-out becomes a `filterNot` step rather than just omitting from a hand-rolled list — slightly less ergonomic for "I don't want bash." Probably worth it: the "I want everything" case is by far the more common one for personal-coding-tool-shaped consumers.

---

## #23 — Ship `CodingMode` next to `ConversationMode` in core ❌

**Location:** New file `core/src/main/scala/sigil/provider/CodingMode.scala`, alongside the existing `ConversationMode.scala`.

**What's missing today:** Sage defines a 3-line case object every coding-shaped Sigil consumer will define identically:

```scala
case object CodingMode extends Mode {
  override val name: String = "coding"
  override val description: String =
    "Writing, debugging, or refactoring code. Optimizes for technical accuracy, " +
      "syntax, and software-engineering principles."
}
```

It's the same shape as `ConversationMode` — bare-minimum mode definition with no skill slot or tool policy override. Every Sigil consumer aimed at a coding workflow will need it. Voidcraft, Sage, and any future agentic-IDE-shaped tool will all benefit from speaking the same `name = "coding"`.

**Suggested fix:** ship `CodingMode` in core with the same shape Sage uses today, plus register it in `Sigil.modes` by default. Apps that don't want it can override:

```scala
override protected def modes: List[Mode] = super.modes.filterNot(_ == CodingMode)
```

`super.modes` would return `List(CodingMode)` (with `ConversationMode` already prepended via `availableModes`). Apps that want only the default conversation mode opt out as above; apps that add modes get coding for free.

Counter-design: leave `Sigil.modes = Nil` as today, ship `CodingMode` as a concrete case object only. Sage-style consumers do `override protected def modes: List[Mode] = List(CodingMode)`. Slightly less magical, slightly more boilerplate.

**Why upstream:** It's a 3-line case object that every coding consumer will define identically. The `name = "coding"` discriminator is what gets persisted in `ModeChange` events and what the `change_mode` tool's argument looks for — having a single canonical version means cross-consumer tooling (analytics, conversation export, etc.) can rely on the discriminator.

**Tradeoff:** None I can see. The shape is the same as `ConversationMode`, the rationale for shipping `ConversationMode` ("every framework user wants this") applies identically. The only real call is whether to include it in `Sigil.modes` by default (slightly more magical) or leave it as a concrete object that consumers list themselves (Sage's current pattern).

---

## #25 — `sigil.codegen.DartGenerator` factory for the build-time scaffolding ❌

**Location:** New file `core/src/main/scala/sigil/codegen/DartGenerator.scala` (or `spice-openapi`-side, since `DurableSocketDartGenerator` already lives there).

**What's missing today:** `Sage.GenerateDart` is ~30 lines of identical setup that every Sigil consumer pays:

```scala
object GenerateDart {
  def main(args: Array[String]): Unit = {
    val outputPath = if (args.nonEmpty) Paths.get(args.head) else Paths.get("../app")
    Sage.polymorphicRegistrations.sync()
    val config = DurableSocketDartConfig(
      serviceName     = "Sage",
      storedEventMode = true,
      infoFields      = List(("conversationId", "String")),
      wireType        = "Signal" -> summon[RW[Signal]].definition,
      clientEventDefs = Nil,
      defTypes        = List("SageInfo" -> summon[RW[SageInfo]].definition),
      durableSubtypes = Sage.eventSubtypeNames
    )
    val generator = DurableSocketDartGenerator(config)
    val files     = generator.generate()
    generator.write(files, outputPath)
    files.foreach(sf => println(s"[${config.serviceName.toLowerCase}:codegen] ${sf.path}/${sf.fileName}"))
    println(s"[${config.serviceName.toLowerCase}:codegen] wrote ${files.size} file(s) under $outputPath")
  }
}
```

The `polymorphicRegistrations` call, the `summon[RW[Signal]].definition` lookup, the `Sage.eventSubtypeNames` reference, the file-write + log formatting — all identical. Only the service name, infoFields, and the optional `defTypes` for the connect-time handshake payload vary.

**Suggested fix:**

```scala
package sigil.codegen

import fabric.rw.*
import rapid.Task
import sigil.Sigil
import sigil.signal.Signal
import spice.openapi.generator.dart.{DurableSocketDartConfig, DurableSocketDartGenerator}

import java.nio.file.{Path, Paths}

/**
 * Build-time helper that wraps `DurableSocketDartGenerator` with the
 * setup every Sigil consumer needs — polymorphic registrations,
 * Signal RW lookup, event-subtype naming for durable routing. Apps
 * call from a small `runMain` shim:
 *
 * {{{
 *   object GenerateDart {
 *     def main(args: Array[String]): Unit =
 *       sigil.codegen.DartGenerator(
 *         sigil       = Sage,
 *         serviceName = "Sage",
 *         infoFields  = List("conversationId" -> "String"),
 *         defTypes    = List("SageInfo" -> summon[RW[SageInfo]].definition)
 *       ).run(args)
 *   }
 * }}}
 */
final case class DartGenerator(
  sigil:       Sigil,
  serviceName: String,
  infoFields:  List[(String, String)],
  defTypes:    List[(String, fabric.rw.Definition)] = Nil,
  storedEventMode: Boolean = true
) {
  def run(args: Array[String]): Unit = {
    val outputPath: Path =
      if (args.nonEmpty) Paths.get(args.head)
      else Paths.get("../app")
    sigil.polymorphicRegistrations.sync()
    val config = DurableSocketDartConfig(
      serviceName     = serviceName,
      storedEventMode = storedEventMode,
      infoFields      = infoFields,
      wireType        = "Signal" -> summon[RW[Signal]].definition,
      clientEventDefs = Nil,
      defTypes        = defTypes,
      durableSubtypes = sigil.eventSubtypeNames
    )
    val generator = DurableSocketDartGenerator(config)
    val files     = generator.generate()
    generator.write(files, outputPath)
    val tag = serviceName.toLowerCase
    files.foreach(sf => println(s"[$tag:codegen] ${sf.path}/${sf.fileName}"))
    println(s"[$tag:codegen] wrote ${files.size} file(s) under $outputPath")
  }
}
```

Then Sage's `GenerateDart.scala` is 5 lines.

**Why upstream:** Build-time-only, but the setup is mechanical and identical, and the wire-shape decisions (e.g., `wireType = Signal`, `durableSubtypes = sigil.eventSubtypeNames`) are exactly what Sigil should be opinionated about. Consumers shouldn't be picking those — they should be inheriting them.

**Tradeoff:** Lower leverage than #20–#24 since it only runs at build time, not on every request. But the boilerplate-to-business-logic ratio in `GenerateDart.scala` is currently ~5:1, and a helper here keeps the codegen invocation surface stable across Sigil versions — if `DurableSocketDartConfig` adds a new required field, the helper sets a sensible default and consumers don't have to touch their `GenerateDart.scala`.

---

**Summary of leverage** (most → least): #20 (`WsServer` factory) is the structural move that pays off across every WebSocket consumer indefinitely. #21 (`getOrCreateConversation`), #22 (`AllShippedTools`), and #25 (`DartGenerator`) collapse boilerplate that every consumer otherwise pays. #23 (`CodingMode`) is trivial but worth doing for cross-consumer discriminator stability. All five are upstream-side moves with no Sage adaptation needed once they ship — Sage's existing code drops to thin overrides at every site.

**Note on UI scope:** an earlier draft of this list included a `SigilChatControllerBase` for Dart consumers. Pulled — Sigil is a backend / wire framework and shouldn't carry UI runtime code. The state-management pattern Sage's controller embodies (snapshot retry, parse-error stream, dispose ordering, `ToolDelta` input patching, etc.) is the right shape, but it lives in Sage and gets extracted into a Sage-published Dart package once a second Flutter consumer of Sigil materializes. Voidcraft is the natural first downstream consumer of that eventual package. Sigil itself stays UI-agnostic.
