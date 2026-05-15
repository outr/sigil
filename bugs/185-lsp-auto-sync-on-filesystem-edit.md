# ❌ #185 — Move LSP / BSP lifecycle notifications from agent-callable tools to framework-managed auto-triggers

**Where:**
- `sigil-tooling` module — currently exposes several LSP /
  BSP lifecycle hooks as agent-callable tools (`LspDidChangeTool`,
  potentially others). Move the underlying capabilities behind
  framework-managed listeners on the tool-result event stream
  + a small set of conversation lifecycle hooks.
- The lifecycle tools themselves — either deleted entirely from
  the agent-callable surface or kept as internal APIs used by
  the new listeners. NOT registered in any consumer's static
  tool roster.

**What's wrong:**

A class of LSP / BSP tools is *infrastructure-shaped state that
exists to keep computed outputs accurate*, not user-meaningful
actions. Exposing them to the agent creates two problems:

1. **The agent forgets.** Every "I edited a file but forgot to
   `lsp_did_change` → diagnostics see stale content → I read
   stale diagnostics → I make a wrong follow-up decision"
   failure is a class of bug the agent shouldn't be responsible
   for.
2. **The agent's roster fills with infrastructure.** Each
   lifecycle tool eats a slot in the roster the ranker has to
   consider, the agent has to triage past, and #184 has to clean
   cross-references out of. The agent's roster should be
   user-meaningful actions; lifecycle plumbing is the framework's
   job.

This bug covers the *family* of these — not just `didChange`.
Every LSP/BSP tool whose purpose is "tell the LSP/BSP server
about a state change" or "fetch state the server has been
emitting on its own schedule" belongs on the auto-trigger side.

### The dividing line

**Infrastructure-shaped (auto-trigger):**
- `textDocument/didOpen` — file first enters the LSP working set
- `textDocument/didChange` — file content changed
- `textDocument/didSave` — file write committed
- `textDocument/didClose` — file no longer of interest
- `publishDiagnostics` surfacing — server's own emission cadence
- `buildTarget/reload` on build-file edit
- BSP-driven diagnostics surfacing
- (Stretch) filesystem-watcher-driven sync for external mutations

**User-meaningful action (stays agent-callable):**
- `bsp_compile` — explicit decision, expensive, drives a workflow
- `git_diff` / `git_status` — agent-driven queries
- `save_memory` — semantic "this is worth keeping" decision
- `lsp_apply_code_action` / `lsp_rename` — refactoring actions
  the agent decides to perform
- `lsp_completion` / `lsp_hover` / `lsp_goto_definition` —
  on-demand queries the agent issues when it wants the info

The test: does this tool *make a thing happen the user/agent
intended*, or does it *keep computed-output-X accurate*? The
former stays agent-callable; the latter moves behind the
framework.

### Suggested implementation

Two listener channels in the `sigil-tooling` module init:

**Channel 1 — tool-result side-effects.** Listens on the
tool-result event stream, filtered by typed projections on the
result body:

```scala
// Tools that touch the filesystem return these via a typed
// mixin, similar to how ToolApproval carries `toolName`:
trait FilesystemTouchingResult {
  def touchedFilePath: Path
  def touchKind: TouchKind  // Read, Write, Edit, Create, Delete
}

// Listener registered at sigil-tooling init:
sigil.onToolResult { case r: FilesystemTouchingResult =>
  val path = r.touchedFilePath
  for {
    lspSession <- lspManager.sessionForFile(path)
    bspSession <- bspManager.sessionForFile(path)
    _ <- r.touchKind match {
      case TouchKind.Read   => lspSession.traverse(_.didOpen(path))
      case TouchKind.Write  => lspSession.traverse(_.didChange(path)) *>
                               lspSession.traverse(_.didSave(path))   *>
                               reloadIfBuildFile(path, bspSession)
      case TouchKind.Edit   => lspSession.traverse(_.didChange(path)) *>
                               lspSession.traverse(_.didSave(path))   *>
                               reloadIfBuildFile(path, bspSession)
      case TouchKind.Create => lspSession.traverse(_.didOpen(path))
      case TouchKind.Delete => lspSession.traverse(_.didClose(path))
    }
  } yield ()
}
```

`reloadIfBuildFile` checks the path against a registered glob
set (`build.sbt`, `build.gradle*`, `pom.xml`, `Cargo.toml`,
`pyproject.toml`, `package.json`, …) for the active BSP build,
and fires `buildTarget/reload` when matched.

**Channel 2 — server-pushed diagnostics surfacing.** LSP /
BSP servers emit `publishDiagnostics` (and BSP compile-report)
events on their own schedules — post-compile, post-save, etc.
These should be available to the agent's next iteration without
the agent having to ask. Implementation: a buffer per
conversation that captures server-pushed diagnostics since the
last agent iteration; on the next turn-context-build, the
framework injects a typed `DiagnosticsSnapshot` frame into the
agent's `ContextFrame` projection.

Critical: this is a *typed* frame, NOT a Tool-role text Message.
Past lessons from #181 / #182 — internal-state-as-Tool-role-text
poisons the agent's context. The diagnostics-injection frame
needs its own type (`ContextFrame.Diagnostics(filePath, items)`)
so consumers and the orchestrator handle it distinctly.

**Fail-soft posture:** any lifecycle sync failure logs at warn
but doesn't fail the originating tool's turn. Stale diagnostics
are better than aborted edits.

### Symmetry with existing infrastructure-shaped pieces

This isn't a new architectural idea in Sigil — several pieces
already work this way:
- **Topic-shift classification** — auto-fired during respond,
  not agent-callable.
- **Memory extractor pipeline** — runs on the curator's schedule
  during compression, not agent-managed.
- **Conversation cost projection** — derived from
  `applyMessageCostToConversation` on every settled Message,
  not agent-callable.
- **Mode projection** — `Conversation.currentMode` updates from
  `ModeChange` events automatically.

The LSP / BSP lifecycle slots in alongside these. The framework
already owns "keep derived state accurate"; this just extends
the same posture to language-server state.

### Apps that don't use LSP / BSP

The listeners register only when the `sigil-tooling` module is
loaded AND there's at least one configured LSP or BSP session.
Apps that never wire either don't pay anything. Apps that wire
one but not the other get only the relevant half.

### Test sketch

Under `core/src/test/scala/sigil/tooling/`:

1. Build a `Sigil` with `sigil-tooling` loaded, an LSP session
   tracking a temp directory, and a BSP session tracking a
   simple Scala project.
2. Run an `edit_file` against a file in the tracked directory.
   Assert: `textDocument/didChange` AND `textDocument/didSave`
   were emitted to the LSP session with the file's new content.
   Build files: also assert `buildTarget/reload` fires for an
   edit to `build.sbt`; non-build files: no reload.
3. Run a `read_file` against a file the LSP hasn't seen yet.
   Assert: `textDocument/didOpen` fires automatically.
4. Run a `read_file` against a file the LSP HAS already seen.
   Assert: no duplicate `didOpen` fires (idempotent).
5. Simulate the LSP server pushing `publishDiagnostics` between
   turns. On the next agent iteration, assert the agent's
   `TurnInput.frames` includes a `ContextFrame.Diagnostics`
   entry for the affected file.
6. Negative: a tool turn that doesn't touch the filesystem
   (e.g. `find_capability`) — assert no sync calls fire.
7. Negative: tracked-directory edit but the LSP session fails
   to accept the didChange (simulated error). Assert the edit's
   turn still completes; only a scribe.warn fires.
8. Assert NONE of `lsp_did_change`, `lsp_did_open`, `lsp_did_save`,
   `lsp_did_close`, `bsp_reload` are in `Sigil.staticTools` —
   apps shouldn't be able to register them accidentally.

### Tool catalog changes

After this lands, the following tools move OUT of the
agent-callable catalog:

- `LspDidChangeTool` → internal API only (used by listener)
- `LspDidOpenTool` → internal API only (likely doesn't exist
  yet as a tool; create the internal API)
- `LspDidSaveTool` → internal API only (same)
- `LspDidCloseTool` → internal API only (same)
- `BspReloadTool` → consider: probably internal API only, since
  build-file edits should auto-trigger reload; explicit
  `bsp_reload` is rarely the right agent surface.

Tools that STAY agent-callable (these are user-meaningful
actions or on-demand queries — different category):

- `LspGotoDefinitionTool`, `LspFindReferencesTool`,
  `LspImplementationTool`, `LspTypeDefinitionTool`
- `LspRenameTool`, `LspPrepareRenameTool`,
  `LspCodeActionTool`, `LspApplyCodeActionTool`
- `LspFormatTool`, `LspFormatRangeTool`
- `LspHoverTool`, `LspSignatureHelpTool`,
  `LspCompletionTool`
- `LspDiagnosticsTool` / `LspPullDiagnosticsTool` (keep — even
  with push-surfacing, on-demand pull is still useful for "give
  me current diagnostics" without waiting for the next turn)
- `LspDocumentSymbolsTool`, `LspWorkspaceSymbolsTool`
- `LspCodeLensTool`, `LspInlayHintsTool`,
  `LspFoldingRangeTool`, `LspSelectionRangeTool`,
  `LspDocumentLinkTool`
- BSP: `BspCompileTool`, `BspTestTool`, `BspRunTool`,
  `BspSourcesTool`, etc. (explicit decisions and queries — all
  stay agent-callable)

### Related

- Bug #184 — tool descriptions reference other tools. Moving
  the lifecycle tools out of the agent-callable surface
  removes the worst cross-reference offender
  (`lsp_did_change`'s "Use AFTER editing a file via `edit_file`
  / `write_file`…") by elimination.
- Bug #183 — `edit_file` silent failure. Composes: the
  auto-sync listener should check the result's disposition
  before firing didChange — no stale didChange for an edit that
  didn't apply.
- Bug #186 — position-based safe-edit primitive. The new tool
  emits the same `FilesystemTouchingResult` typed mixin, so the
  listener works against both string-based and position-based
  edits without special-casing.
- Bug #187 — refactoring suite discoverability. Refactor tools
  (lsp_rename, lsp_apply_code_action) ALSO produce filesystem
  edits internally; their results emit the same typed mixin and
  ride the same auto-sync.
