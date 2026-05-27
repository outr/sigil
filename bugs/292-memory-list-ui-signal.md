# ❌ #292 — RequestMemoryList / MemoryListSnapshot signal pair

**Where:** `sigil/signal/`. Consumer filed from Voidcraft
`backend/.../server/MemoriesEndpoint.scala` (which should disappear once this
signal pair exists) and `app/lib/widget/panel/memory_browser_panel.dart`
(today consumes a bespoke REST endpoint).

**What's wrong:** Sigil owns `ContextMemory` and the Lucene-backed
`db.memories` collection. The agent reads memory via `ListMemoriesTool` /
`SaveMemoryTool` / `MemoryHistoryTool`, but a UI browser panel that lists
"every memory the viewer owns" has no signal surface — apps have to expose
their own REST endpoint and re-implement the filter logic (createdBy =
viewer, memoryType, pinned, location). Voidcraft just did exactly this in
`MemoriesEndpoint`; that endpoint is pure duplication of state Sigil already
holds.

The agent-side already has `ListMemoriesOutput` / `MemoryListPage` /
`MemoryListEntry` types under `sigil.tool.model` — the wire shape exists,
only the Notice pair is missing.

**Suggested fix:** Mirror the `RequestConversationList` pattern. Add:

```scala
// sigil/signal/RequestMemoryList.scala
case class RequestMemoryList(query: Option[String] = None,
                             memoryType: Option[MemoryType] = None,
                             pinned: Option[Boolean] = None,
                             hasLocation: Boolean = false,
                             limit: Int = 100) extends Notice derives RW

// sigil/signal/MemoryListSnapshot.scala
case class MemoryListSnapshot(memories: List[MemoryListEntry]) extends Notice derives RW
```

Default arm in `Sigil.handleNotice`:

```scala
case r: sigil.signal.RequestMemoryList =>
  withDB(_.memories.transaction(_.query.toList)).flatMap { all =>
    val filtered = all
      .filter(_.createdBy.exists(_.value == fromViewer.value))
      .filter(m => r.memoryType.forall(_ == m.memoryType))
      .filter(m => r.pinned.forall(_ == m.pinned))
      .filter(m => !r.hasLocation || m.location.isDefined)
      .filter(m => r.query.forall(q =>
        m.fact.toLowerCase.contains(q.toLowerCase) ||
          m.label.toLowerCase.contains(q.toLowerCase) ||
          m.summary.toLowerCase.contains(q.toLowerCase)))
      .take(r.limit)
    publishTo(fromViewer, MemoryListSnapshot(filtered.map(MemoryListEntry.from)))
  }
```

Reuses `MemoryListEntry.from(ContextMemory)` already defined in
`sigil.tool.model.MemoryListEntry`.

Once shipped, Voidcraft can delete `MemoriesEndpoint.scala`,
`voidcraft_memories_client.dart`, and the three browser-panel HTTP loaders —
the panels become `controller.memories` watchers on the existing tome
controller signal stream.
