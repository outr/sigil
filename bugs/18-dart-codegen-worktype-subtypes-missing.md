# ❌ #18 — Dart codegen: framework `WorkType` subtypes invisible to the generator

**Status:** Surfaced as the lone residual after the bug-#16 fix
landed (`5e22eb8` "route case-object defaults via parent-side
singletons"). The codegen now correctly emits `WorkType.conversationWork`
in consumer files like `DefaultAgentParticipant`, but the parent-class
Dart file is empty — no subtype singletons, no `fromJson` dispatch.
`Mode` and `SpaceId` got their subtype tables correctly; `WorkType`
didn't.

**Where:**
- `core/src/main/scala/sigil/Sigil.scala:3880-3886` — Sigil hard-codes
  the framework-shipped `WorkType` subtypes (`ConversationWork`,
  `CodingWork`, `AnalysisWork`, `ClassificationWork`, `CreativeWork`,
  `SummarizationWork`) and appends `workTypeRegistrations` for fabric
  RW poly-registration. The Dart codegen pipeline doesn't see this
  list.
- `core/src/main/scala/sigil/Sigil.scala:144` — `workTypeRegistrations:
  List[WorkType] = Nil` is meant for *app-defined* subtypes per the
  scaladoc on `WorkType.scala:23`. Apps don't (and shouldn't have to)
  re-register the framework's own.

**Concrete repro on Sage codegen against latest spice + sigil
publishLocal:**

Generated `WorkType` Dart class:
```dart
/// GENERATED CODE: Do not edit!
abstract class WorkType {
  const WorkType();
  Map<String, dynamic> toJson();
  static WorkType fromJson(Map<String, dynamic> json) {
    final type = json['type'] as String?;
    throw ArgumentError('Unknown WorkType type: $type (keys: ${json.keys.join(", ")})');
  }
}
```

Empty body — no `static const WorkType conversationWork = ...`, no
type-dispatch in `fromJson`. So the consumer reference

```dart
this.workType = WorkType.conversationWork
```

emitted by `DefaultAgentParticipant`'s constructor (per the bug-#16
fix's "parent-side singletons" routing) fails: "The getter
'conversationWork' isn't defined for the type 'WorkType'."

Compare with `Mode` and `SpaceId`, which the codegen filled in
correctly:

```dart
// mode.dart
abstract class Mode {
  const Mode();
  static const Mode conversationMode = ConversationMode();
  static const Mode workflowBuilderMode = WorkflowBuilderMode();
  static const Mode scriptAuthoringMode = ScriptAuthoringMode();
  static const Mode codingMode = CodingMode();
  static const Mode webBrowserMode = WebBrowserMode();
  // + fromJson dispatch on each
}

// space_id.dart
abstract class SpaceId {
  const SpaceId();
  static const SpaceId globalSpace = GlobalSpace();
  static const SpaceId sageSpace = SageSpace();
  // + fromJson dispatch
}
```

These got their full subtype tables because:
- `Mode`'s subtypes flow through `Sigil.modes` (the `def modes`
  override) which collects framework + mixin + app-defined modes
  into one list the codegen iterates.
- `SpaceId`'s subtypes flow through `Sigil.spaceIds` (the `def
  spaceIds` override) similarly.
- `WorkType` has no `def workTypes` collector. Sigil's hardcoded list
  at line 3880 + the app-level `workTypeRegistrations` are the two
  sources, but only the latter is reachable from the codegen path.

**Suggested fix (option A — symmetric with Mode/SpaceId):** add a
`workTypes` def to `Sigil` that returns the union of framework-shipped
+ app-registered subtypes, and have the Dart codegen iterate it just
like it does `modes` and `spaceIds`:

```scala
// Sigil.scala
protected def workTypes: List[WorkType] = List(
  ConversationWork,
  CodingWork,
  AnalysisWork,
  ClassificationWork,
  CreativeWork,
  SummarizationWork
) ++ workTypeRegistrations

// In the codegen-source iteration, treat WorkType the same as Mode/SpaceId.
```

The hardcoded list at line 3880 then collapses into `workTypes`. The
existing `workTypeRegistrations` extension point stays exactly as
documented — apps register their domain-specific subtypes via that
override.

**Suggested fix (option B — codegen-side):** if the codegen has a
generic "scan poly-registered subtypes for a given trait" mechanism
(it'd need to for option A to work too), use that path uniformly for
all polymorphic types instead of relying on Sigil-side `def`
overrides. Either path produces the same Dart output; A keeps Sigil's
existing collector pattern, B reduces collector boilerplate.

**Backward compatibility:** option A is purely additive. Apps that
have overridden `workTypeRegistrations` keep working. The only
behavior change is the Dart `WorkType` class gaining six static
singletons + a non-empty `fromJson` dispatch.

**What Sage does today:** waits. The single residual error blocks
`DefaultAgentParticipant`'s Dart class from compiling, which cascades
into anything that references it. Hand-written code stays clean —
this is purely a generated-code gap.

**Why this is the framework's job:** consumers shouldn't have to
re-register framework-shipped subtypes. `workTypeRegistrations`'s
documented purpose is "your subtypes" — the framework's own should
ride for free, same as `Mode` and `SpaceId` do.
