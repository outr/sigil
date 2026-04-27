# Bugs

Tracking bugs found in Sigil, Spice, Fabric, and related libraries while building a downstream consumer on top of Sigil. Each entry: where it lives, what's wrong, what we observed, suggested fix.

**Status legend:** ⚠️ workaround in place · ❌ open · ✅ fixed.

## Sigil

### 1. ✅ Polymorphic registrations are coupled to DB init; codegen tasks force a RocksDB lock acquire

**Where:** `~/projects/open/sigil/core/src/main/scala/sigil/Sigil.scala`.

**Symptom (was):** Codegen tasks needing the polymorphic discriminators populated had to call `Sigil.instance.sync()`, which opened the LightDB / RocksDB store. With a live backend already holding `db/sigil/LOCK`, every codegen run failed at boot. Developer had to stop the server, regen, restart — every wire-type change.

**Fix landed:** `Sigil` now exposes a two-phase lifecycle:

- `Sigil.polymorphicRegistrations: Task[Unit]` — phase-1, populates every fabric `PolyType` discriminator (Signal, ParticipantId, Mode, ToolInput, Participant, Tool, SpaceId) with the registered subtypes. Pure JVM-level effect; does NOT open the DB. Idempotent (`.singleton`).
- `Sigil.instance: Task[SigilInstance]` — phase-2; runs `polymorphicRegistrations` first (so the runtime sees the same registration ordering as before), then opens the DB, applies upgrades, etc.

Sage's `GenerateDart` was migrated to call `Sage.polymorphicRegistrations.sync()` instead of `Sage.instance.sync()` — no RocksDB lock acquired during codegen, no contention with a running server. Also dropped `Sage.shutdown.sync()` from the codegen path since there's no DB to dispose. `Sigil.shutdown` was hardened to skip DB-dispose when `instance` was never started (e.g. codegen-only paths), so even if a stray shutdown call leaks in, it doesn't force the DB open.

381/381 Sigil tests pass on the new lifecycle. Sage codegen now runs with a live backend in the next terminal — verified.

---

### 3. ✅ `SignalTransport.attach` returns before the live subscription is hot — early publishes are dropped

**Where:** `~/projects/open/sigil/core/src/main/scala/sigil/pipeline/SignalHub.scala` (the underlying race) and `~/projects/open/sigil/core/src/main/scala/sigil/transport/SessionBridge.scala` (the related "no replay on connect" symptom).

**Symptom (was):** Sage's per-session bridge attached the sink, then `Sigil.fireGreeting` published the greeting Events to the SignalHub. Two distinct losses combined:

1. **Race in `SignalHub.subscribe`** — the queue was registered lazily (only on the first pull via `Stream.using`'s setup task). `SignalTransport.attach`'s drain fiber was started fire-and-forget; any `emit()` between `attach` returning and the fiber's first pull bypassed the not-yet-registered subscriber.
2. **No replay on session start** — `SessionBridge.attach` defaulted `resume = ResumeRequest.None`, so reconnects (and connections that arrived after the greeting was already published+persisted) never replayed history. Even with the race fixed, a browser that connects 200ms after a greeting fires would see nothing because replay was off.

**Fix landed (option #1 from the original report — eager registration — plus a sensible default for replay):**

- **`SignalHub.subscribe`** now registers the subscriber's queue *synchronously* before constructing the stream value. Any `emit()` between the call and the consumer's first pull is queued for the consumer rather than dropped. The cleanup path on stream termination is unchanged (close-sentinel / error / consumer short-circuit removes the queue from the hub).
- **`SessionBridge.attach`** gained a `resume: ResumeRequest = SessionBridge.DefaultResume` parameter, where `DefaultResume = ResumeRequest.RecentMessages(50)`. Apps that want live-only sessions pass `ResumeRequest.None` explicitly.

The `Task.sleep(200.millis)` settle the old `ConversationHarness` had is no longer needed (the race that motivated it is gone) and was already removed during the SessionBridge refactor.

**Tests:**
- `SignalHubSpec` (3 tests, deterministic): `subscriberCount` reflects new sub before stream consumption; emissions between subscribe and stream consumption are delivered; close sentinel removes the subscriber. All 3 fail on pre-fix code, pass on post-fix.
- `SignalTransportSpec` "register the live subscription synchronously — no signal loss for publishes that race attach()": 50-publish loop immediately after attach with no sleep; passes deterministically post-fix.

385/385 Sigil tests pass on the new lifecycle. Sage's greeting now reaches the browser on connect — verified via the (re)connect path that triggers `RecentMessages(50)` replay.

## spice-openapi (Dart codegen)

### 2. ✅ Generated Dart enums lowercase case names; round-trip with Scala fails

**Where:** `~/projects/open/spice/openapi/src/main/scala/spice/openapi/generator/dart/DurableSocketDartGenerator.scala` — Dart enum emission from a Scala enum's polymorphic Definition.

**Symptom (was):** Generated Dart `Message.toJson()` emitted the enum value via `state.name`, which Dart lowercases (`"complete"`). The Scala server's `RW[EventState]` decoded case-sensitively against `enum EventState { case Active, case Complete }`, mismatched, threw `RWException: enum sigil.signal.EventState has no case with name: complete`. Affected every parameterless poly emitted as a Dart enum: `EventState`, `MessageRole`, `AgentActivity`, etc.

**Fix landed (option #2 from the original report):** generated Dart enums now carry an explicit wireName mapping so the toJson path preserves the original Scala case while the Dart-side enum identifier stays lint-clean (lowercase first char). Concretely:

```dart
enum EventState {
  active,
  complete;

  static const Map<EventState, String> _wireNames = {
    EventState.active: 'Active',
    EventState.complete: 'Complete',
  };

  String get wireName => _wireNames[this]!;

  static EventState? fromString(String? value) {
    if (value == null) return null;
    final lower = value.toLowerCase();
    return EventState.values.cast<EventState?>().firstWhere(
      (v) => v?.name.toLowerCase() == lower,
      orElse: () => null,
    );
  }
}
```

The `defTypeToJsonExpr` path in the generator was changed from `$access.name` → `$access.wireName` for simple-enum poly fields. `fromString` stays case-insensitive (handles either `"complete"` or `"Complete"` from any source).

Option #1 (emit Dart cases in original Scala case) was rejected because it would force every Sage handwritten Dart reference (`EventState.complete` etc.) to flip case across the consumer codebase. Option #2 keeps consumer code unchanged.

89/89 spice-openapi tests pass on the new generator. Sage regenerates its Dart on next codegen run; existing `EventState.complete` references in Sage's Flutter code keep working unchanged.
