# 💡 #244 — Unify tool pagination as immutable containers with stable IDs; consumer tools (like `dispatch_workers`) take only an `itemsId`. Supersedes #230's multi-arm `WorkerItemSource`.

**Status:** Design note. Architectural change. Supersedes part of
#230 (the `WorkerItemSource` union).

**Where (proposed):**
- `tooling/src/main/scala/sigil/tooling/container/` (new package)
  — producer / filter / consumer-facing tools that operate on
  containers.
- `core/src/main/scala/sigil/tool/output/ToolOutputNode.scala`
  — already exists; this design uses it as the universal
  container persistence. May need a non-TTL'd variant or a
  "pin until conversation-level cleanup" flag (see TTL section).
- `tooling/src/main/scala/sigil/tooling/dispatch/DispatchWorkersInput.scala`
  — collapse the `WorkerItemSource` union down to a single
  `itemsId: Id[ToolOutputNode]` field. Migration from #230's
  current shape below.
- `core/src/main/scala/sigil/Sigil.scala` — conversation-level
  cleanup pass that ages out containers when the conversation
  reaches a size or age threshold (replaces per-row TTL).

**Motivation:**

#230 defined `WorkerItemSource` as a sealed-trait union with
four arms (`FromCall`, `FromList`, `FromFile`, `FromConversation`)
so `dispatch_workers` could accept items from any source. Each
new source type would add a new arm. Every other consumer tool
that wants to operate over a list ends up duplicating the same
union (or re-inventing its own variant).

Field evidence — Sage 2026-05-19 12:09:32:

```
ToolInvoke(toolName: "dispatch_workers", input: {
  "items": { "type": "FromCall", "callId": "RRWh...", "groupBy": "TopLevelOnly" },
  ...
})
```

The `FromCall` case is the only one actually used in practice
— the agent ran grep, got a callId, fed it to dispatch_workers.
The other arms (FromList, FromFile, FromConversation) exist
in the spec but the agent reached for the natural pattern of
"pipe one tool's output into another's input." So we're paying
the cost of the union without using its flexibility.

Bigger picture: Sigil ALREADY has the infrastructure for
"named, paginated, immutable result tables." `ToolOutputNode`
holds tool output rows keyed by `callId` + `referenceId` + level;
`next_page(referenceId)` walks them; `query_tool_output(callId,
containsText)` flat-filters them. Every paginated tool already
writes to this table. The missing piece is to elevate "callId
that contains paginated items" into a first-class concept that
ANY consumer tool can take as input, with one canonical shape.

**Suggested design:**

### Core abstraction: the container is a callId + its
`ToolOutputNode` subtree

A "container" is exactly what Sigil's paginated-tool infrastructure
already produces: a stable `callId` (or any `referenceId`) under
which a tree of rows lives. The container is **immutable** — rows
get added by the producer, read by consumers, transformed via
new derived containers, but never mutated in place.

No new persistence layer. Just elevate the existing one to a
first-class input-shape concept.

### Consumer side: one canonical input field

Every consumer tool that operates over a list takes the same
shape:

```scala
itemsId: Id[ToolOutputNode]   // any container's root id

// Optional standard refinements all consumers share:
itemsAt:  Option[Int] = None  // which level of the tree to consume
                              // (top-level by default)
itemsLimit: Option[Int] = None  // hard cap on items consumed
```

`dispatch_workers` becomes:

```scala
case class DispatchWorkersInput(
  itemsId: Id[ToolOutputNode],
  pipeline: WorkerPipeline,
  complexity: Complexity,
  workerModelId: Option[String] = None,
  maxParallel: Int = 5,
  maxItems: Int = 10000,
  confirmed: Boolean = false
)
```

No `WorkerItemSource` union. No `FromList` / `FromCall` /
`FromFile` / `FromConversation` arms. The consumer accepts ONE
shape; the producer-side variety lives in separate tools that
each return an `itemsId`.

### Producer side: tools that materialize containers

Most producers already exist (grep, lsp_workspace_symbols, every
paginated tool). For the cases that don't fit, add small
producer tools:

```scala
// Inline list → container. Used when the agent constructed a
// list from reasoning ("the four files mentioned in turn 3")
// and wants to operate over it.
case object CreateContainerTool extends TypedTool[CreateContainerInput](
  name = ToolName("create_container"),
  description =
    """Persist an inline list of items as a paginated container and return its
      |containerId. Pass the containerId to any consumer that takes one (e.g.
      |dispatch_workers). Useful when you've identified a set of items through
      |reasoning rather than tool output and want to operate over them.""".stripMargin
)
case class CreateContainerInput(items: List[Json]) extends ToolInput derives RW
case class CreateContainerOutput(itemsId: Id[ToolOutputNode],
                                 itemCount: Int) derives RW

// File → container. One-item-per-line, JSON array, CSV, or
// regex-split.
case object LoadFileAsContainerTool extends TypedTool[LoadFileAsContainerInput]
case class LoadFileAsContainerInput(
  filePath: String,
  parser: ItemParser
) extends ToolInput derives RW

// Filter an existing container into a new derived container —
// the source is untouched.
case object FilterContainerTool extends TypedTool[FilterContainerInput]
case class FilterContainerInput(
  sourceId: Id[ToolOutputNode],
  /** Predicate evaluated against each item's payload Json.
    * Two convenience forms: a simple substring match, or a
    * JsonPath-style query that resolves truthy/falsy. */
  predicate: ContainerPredicate
) extends ToolInput derives RW

sealed trait ContainerPredicate derives RW
object ContainerPredicate {
  case class Contains(text: String) extends ContainerPredicate
  case class JsonPath(path: String, equals: Option[Json] = None) extends ContainerPredicate
  case class RegexMatch(field: String, pattern: String) extends ContainerPredicate
}
```

### Composition becomes obvious

Agent's flow for the common cases:

**"Refactor every file with 'bug' in a Scala comment":**

```
grep(pattern, glob)                                       → C1
dispatch_workers(itemsId=C1, pipeline, confirmed=false)   → ScopePreview(47 files, ~$0.12)
dispatch_workers(itemsId=C1, pipeline, confirmed=true)    → DispatchResult
```

**"Refactor a specific subset from grep":**

```
grep(pattern, glob)                                       → C1
filter_container(sourceId=C1, predicate=Contains("core/"))→ C2
dispatch_workers(itemsId=C2, pipeline, confirmed=true)    → DispatchResult
```

**"Operate over a list from user input":**

```
create_container(items=[file1, file2, file3])             → C1
dispatch_workers(itemsId=C1, pipeline, confirmed=true)    → DispatchResult
```

**"Classify references found by LSP":**

```
lsp_find_references(symbol)                               → C1
dispatch_workers(itemsId=C1, pipeline_for_classification) → DispatchResult
```

Every flow uses the same `itemsId` shape. The container plumbing
is invisible — it's just "pipe tool A's output id into tool B's
input."

### TTL / cleanup — conversation-level, not per-row

**No default per-row TTL.** Storage is cheap; arbitrary 30-minute
expiries cause "agent finished thinking, came back to apply, lost
the container" failures that are confusing and unrecoverable.

Cleanup at conversation-level instead. Two triggers:

1. **Age**: containers older than the conversation's idle-since
   timestamp by a configurable window (default 30 days) get pruned
   when the conversation is "stale-cleaned" (a periodic background
   pass).
2. **Size**: when a conversation's total `ToolOutputNode` rows
   cross a threshold (default 100K rows or 100MB), the oldest
   containers get pruned in FIFO order until the size budget is
   met. Prevents runaway storage on a single very-long
   conversation.

Both are coarse-grained — they don't fire mid-workflow. A
container created and consumed in the same agent loop never gets
GC'd by these passes (it's brand new). A container left around
from yesterday's conversation when the user comes back today is
still there (well within the age window).

For workflows that legitimately span very long timeframes (a
conversation paused for a week), the user / app can add an
explicit `pin_container(containerId)` to mark a container as
"do not GC" — the conversation-level pass skips pinned rows.

This replaces the per-row `expiresAt` field on `ToolOutputNode`
(make it nullable or remove entirely; persisted historical data
keeps whatever value is there).

### Migration from #230

The current `WorkerItemSource` union:

```scala
// OLD (#230)
items: WorkerItemSource where WorkerItemSource = FromCall | FromList | FromFile | FromConversation
```

Becomes:

```scala
// NEW (this bug)
itemsId: Id[ToolOutputNode]
```

With the per-arm replacement:

| Old WorkerItemSource arm | New flow                                                         |
|--------------------------|------------------------------------------------------------------|
| `FromCall(callId)`       | Pass the callId directly as `itemsId`                            |
| `FromList(items)`        | Call `create_container(items)`, pass returned `itemsId`          |
| `FromFile(path, parser)` | Call `load_file_as_container(path, parser)`, pass returned id    |
| `FromConversation(...)`  | (Sage-specific; can add `extract_from_conversation` producer)    |

The `GroupBy` semantics (PerItem vs ByKey) move onto the
consumer-side `itemsAt: Option[Int]` field — choose which level
of the tree to consume.

If #230's shipped multi-arm union has consumers in the wild,
provide a one-release deprecation cycle: accept BOTH shapes; log
a deprecation warning when the union form is used; remove the
union after one SNAPSHOT.

### Failing tests

Under `core/src/test/scala/spec/PaginatedContainerSpec.scala`:

1. **CreateContainer + dispatch_workers round-trips:**
   - `create_container([{name: "a"}, {name: "b"}, {name: "c"}])` → containerId
   - `dispatch_workers(itemsId=containerId, ..., confirmed=true)` runs 3 workers.

2. **Filter narrows a container into a new container:**
   - Source container has 100 items.
   - `filter_container(sourceId, Contains("foo"))` → derived container.
   - Assert: source still has 100; derived has <100; derived id != source id.

3. **No per-row TTL by default:**
   - Create a container; advance the mock clock 24 hours.
   - Read it back via `next_page` — assert items still present.

4. **Conversation-level cleanup at age threshold:**
   - Create containers across a long-stale conversation.
   - Advance mock clock past the age threshold.
   - Run the stale-cleanup pass.
   - Assert: containers in the stale conversation are gone; containers
     in active conversations untouched.

5. **`pin_container` skips conversation cleanup:**
   - Create two containers in a stale conversation; pin one.
   - Run cleanup.
   - Assert: pinned container remains; un-pinned is gone.

### Why this is the right shape now (not later)

#230 is filed but the implementation may still be in flux —
this is the right window to fix the input shape before
consumers grow up around `WorkerItemSource`. The longer the
multi-arm union sits, the more tools will reach for it
(future `dispatch_workers`-like primitives), and the more
migration cost the refactor incurs.

The bonus: this lands `filter_container`, `create_container`,
and the producer/consumer composition pattern as deliberate
primitives, opening up many future patterns ("score these,
take top 10, dispatch_workers over the top 10" becomes three
tool calls instead of one custom tool).

**Related:**
- **#230** — `dispatch_workers` redesign. This bug supersedes
  the `WorkerItemSource` union with a single `itemsId` shape.
  The pipeline / complexity / confirmed-flow design stays.
- **#243** — `dispatch_workers(confirmed=false)` ScopePreview
  bug. Independent — applies regardless of input shape. After
  this bug lands, the ScopePreview's `confirmCall` text
  references `itemsId` instead of `items`.
- **`ToolOutputNode`** — existing persistence the container
  concept builds on. Already has `callId` / `referenceId` /
  level / payload — exactly the right primitives.
- **`next_page` / `query_tool_output`** — existing read-side
  tools; this design adds the write-side primitives that
  symmetric with them.
- **Sage event log (2026-05-19 12:09:32)** — field evidence
  of the only-FromCall-is-used pattern.
