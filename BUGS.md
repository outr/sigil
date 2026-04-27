# Bugs

Tracking bugs found in Sigil, Spice, Fabric, and related libraries while building a downstream consumer on top of Sigil. Each entry: where it lives, what's wrong, what we observed, suggested fix.

**Status legend:** ✅ fixed · ⚠️ workaround in place · ❌ open.

## spice-openapi (Dart codegen)

### 1. ⚠️ Polymorphic `fromJson` dispatch generated empty for runtime-registered subtypes

**Where:** `~/projects/open/spice/openapi/src/main/scala/spice/openapi/generator/dart/DurableSocketDartGenerator.scala`

**Symptom:** A `Sigil` extension registers concrete `ParticipantId` subtypes via `instance.sync()` before running the codegen. A diagnostic confirms `summon[RW[ParticipantId]].definition` reports both subtypes at codegen time. But the generated `participant_id.dart` contains a `fromJson` with zero cases — just the trailing `throw ArgumentError(...)`. Decoding any incoming `Message` event whose `participantId` is the registered subtype fails at runtime in the browser.

**Suspected cause:** the generator walks `summon[RW[Signal]].definition` from `defTypes` and recurses into Message's fields. The field's stored Definition for `ParticipantId` snapshots the poly's `values` AT RW-DERIVATION TIME — before the runtime registration. The live `summon[RW[ParticipantId]].definition` returns fresh data, but the Message-nested copy doesn't.

**Workaround (Sage-side):** explicitly include `ParticipantId` in `defTypes` BEFORE `Signal`, so `collectTypes` registers the live poly first and short-circuits when the field-traversal reaches the same name later:

```scala
defTypes = List(
  "ParticipantId" -> summon[RW[ParticipantId]].definition,  // live, with subtypes
  "Signal" -> summon[RW[Signal]].definition,
  ...
)
```

Confirmed working — generated `ParticipantId.fromJson` now dispatches to every registered subtype.

**Real fix (still pending in spice):** the generator should always re-resolve poly Definitions through their `className` against a live registry rather than trusting the field's stored snapshot. That requires fabric to expose a way to look up `RW[T]` by `className`, which doesn't exist today.

---

### 2. ✅ `toJson` `'type'` discriminator uses Dart-renamed class name instead of the original wire discriminator

**Status:** Fixed in spice 1.8.0-SNAPSHOT. Generated `toJson` now emits the original poly key (e.g. `'sigil.tool.model.ResponseContent.Text'`) rather than the dart-renamed class name (`'TextContent'`). Verified by re-running codegen after upgrading.

---

## Sigil

### 3. ✅ `Sigil` minimal extension surface is heavy

**Status:** Fixed. Defaults shipped on `Sigil`'s previously-abstract members: `signalRegistrations`, `participantIds`, `spaceIds`, `participants` default to `Nil`; `curate` defaults to `TurnInput(view)`; `getInformation` defaults to `Task.pure(None)`; `putInformation` defaults to `Task.unit`; `embeddingProvider` defaults to `NoOpEmbeddingProvider`; `vectorIndex` defaults to `NoOpVectorIndex`; `wireInterceptor` defaults to `Interceptor.empty`; `compressionMemorySpace` defaults to `Task.pure(None)`.

The minimum viable Sigil extension is now `buildDB` + `providerFor` only. Apps that customize the other hooks override; apps that don't have a much smaller surface to manage.

---

### 4. ✅ `Sigil` minimal subclass doesn't terminate the JVM in one-shot CLI mode

**Status:** Fixed. `Sigil.shutdown(): Task[Unit]` releases shared resources — disposes the LightDB instance and signals the model-refresh background fiber to stop on its next iteration. Idempotent. CLI / one-shot consumers call this before returning from `main` so the JVM exits cleanly without `System.exit`.

The model-refresh loop now checks `isShutdown` before each iteration.

---

### 5. ✅ `MessageDelta` semantics non-obvious — `complete=true` re-delivers the whole block

**Status:** Fixed via documentation. The `MessageDelta` doc comment now explicitly describes the two streaming modes (incremental `complete=false` vs snapshot-only `complete=true`) and the contract that subscribers MUST pick one or the other or they double-render. The orchestrator-side persistence path uses snapshot-only; UI subscribers can pick either.

---

### 6. ✅ `ContentDelta` is nested inside `MessageDelta`, not a `Signal` itself

**Status:** Fixed. Renamed `ContentDelta` → `MessageContentDelta` so the type's relationship to `MessageDelta` is explicit at the type name. `git mv` was used; all call sites in core, tests, and codegen output updated. Doc comment now reads: "Named `MessageContentDelta` (not `ContentDelta`) so the type's relationship to `MessageDelta` is explicit — these never appear standalone on the signal stream; they always travel inside a `MessageDelta`."
