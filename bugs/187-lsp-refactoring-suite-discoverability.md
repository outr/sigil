# ❌ #187 — LSP refactoring tools exist in `sigil-tooling` but don't reach the agent's roster for refactor-shaped queries

**Where:**
- `tooling/src/main/scala/sigil/tooling/` — Sigil already ships
  an extensive LSP suite (28 LspXTool files). The relevant
  refactor-shaped ones:
  - `LspRenameTool` — symbol-aware rename across project
  - `LspPrepareRenameTool` — validate rename preflight
  - `LspCodeActionTool` — enumerate available code actions
  - `LspApplyCodeActionTool` — apply a chosen code action
  - `LspGotoDefinitionTool` — find symbol definition
  - `LspTypeDefinitionTool` — find type definition
  - `LspImplementationTool` — find implementations of a
    trait/interface
  - `LspFindReferencesTool` — find all references to a symbol
  - `LspFormatTool` / `LspFormatRangeTool` — formatting
  - `LspHoverTool` — hover info (type signatures, scaladoc)
  - `LspSignatureHelpTool` — function signature help
  - `LspCompletionTool` — completions
  - `LspDocumentSymbolsTool` — outline / document symbols

- Discoverability surface:
  - `find_capability`'s BM25-style keyword ranker (mentioned in
    bug #158's history).
  - Mode tool-roster policies (e.g. `CodingMode.tools:
    ToolPolicy`).

**What's wrong:**

The tools exist. They are NOT reaching the agent's roster when
the agent searches for edit-shaped / refactor-shaped queries.

Evidence from a live Sage session (2026-05-15): the agent had
this 16-tool roster at the moment it chose `edit_file` for what
should have been a refactor:

```
change_mode, find_capability, record_consent, read_file, grep,
bsp_sources, lsp_diagnostics, edit_file, write_file,
lsp_did_change, bsp_inverse_sources, lsp_folding_range,
git_diff, cancel, respond_options, respond
```

`lsp_rename`, `lsp_code_action`, `lsp_apply_code_action`,
`lsp_goto_definition`, `lsp_find_references`,
`lsp_implementation`, `lsp_type_definition`, `lsp_format`,
`lsp_hover`, `lsp_completion`, `lsp_signature_help` are ALL
absent. The agent had no semantic-refactor option in scope, so
the only edit it could reach was `edit_file` — which then
silently failed on a whitespace mismatch (bug #183).

The most recent `find_capability` query in that session was
`"view file source contents read code lines"`. Looking at the
top 10 matches by score, NONE of the LSP refactor tools
appeared:

```
score=61  read_file
score=17  grep
score=17  bsp_sources
score=16  lsp_diagnostics
score=15  script-authoring (Mode)
score=14  edit_file
score=14  write_file
score=12  lsp_did_change
score=10  …
```

The ranker's keyword-matching prioritized `read_file` and
filesystem-edit tools, never surfaced the LSP refactor family.

### Root cause: keyword surface + categorization

The find_capability ranker is keyword-based. For a refactor tool
to score on an edit-shaped query ("change", "modify", "refactor",
"fix"), its description / keyword set has to include those
terms. Without inspecting each tool's current description, the
empirical evidence is that they don't — none of the refactor
suite scored above the default-roster cutoff for an edit-shaped
query.

A second axis: `CodingMode`'s tool policy. Modes are
pre-curated rosters. `CodingMode.tools = ToolPolicy.Standard`
today (per `core/src/main/scala/sigil/provider/CodingMode.scala`),
which means it doesn't proactively include the refactor suite.
Even when the agent IS in CodingMode, it has to discover each
refactor tool individually via `find_capability` — and the
ranker isn't surfacing them.

### Suggested fix (two layers)

**A — keyword-surface audit on the refactor suite.** Each
refactor tool needs keywords that match the queries an agent
actually issues when wanting to edit code. Examples:

- `LspRenameTool` — keywords: `rename, refactor, change name,
  symbol rename, identifier, refactoring, modify, update,
  replace`
- `LspApplyCodeActionTool` — keywords: `refactor, fix, quickfix,
  apply, code action, extract method, extract variable,
  organize imports, missing imports, modify, change, transform`
- `LspGotoDefinitionTool` — keywords: `definition, navigate,
  jump to, source, find, where defined, declaration`
- `LspFindReferencesTool` — keywords: `references, usages,
  find all, callers, uses, who calls, occurrences`
- `LspImplementationTool` — keywords: `implementations,
  subclasses, implementors, who implements, traits, interface`
- `LspHoverTool` — keywords: `type, hover, info, type of,
  scaladoc, documentation, what is`
- `LspFormatTool` — keywords: `format, indent, reformat,
  pretty-print, scalafmt, fix formatting`
- `LspCompletionTool` — keywords: `complete, suggest,
  autocomplete, what can I call here`

Pair with descriptions that don't reference other tools (per
bug #184).

**B — wire LSP refactor tools into `CodingMode`'s default tool
policy.** When the conversation is in `CodingMode` AND an LSP
session is active for the workspace's language, the mode's
ToolPolicy should auto-include the refactor suite as available
tools — same way the standard core roster is. The agent
shouldn't need to `find_capability` for `lsp_rename` to use it
during coding work; it should be in-scope by default.

Implementation shape:

```scala
case object CodingMode extends Mode {
  override val name = "coding"
  override val workType = Some(CodingWork)
  override def tools: ToolPolicy = ToolPolicy.Standard.appendIf(
    sigil.tooling.LspToolSupport.refactorSuite,
    when = workspaceHasActiveLspSession
  )
  …
}
```

(Conditional inclusion keeps non-LSP-using consumers paying
nothing.)

**C (optional, follow-up) — ranker awareness of workspace
state.** Per bug #167's pattern of threading conversation state
into the classifier, the `find_capability` ranker could weight
LSP tools higher when an LSP session is active for the
conversation's workspace. Lower-priority than A and B — those
two should cover the discoverability gap.

### Test sketch

Under `core/src/test/scala/sigil/tooling/`:

1. Build a `Sigil` with `sigil-tooling` loaded, a `CodingMode`
   conversation, and an LSP session active for the workspace.
2. Issue refactor-shaped `find_capability` queries
   ("rename method", "extract variable", "find usages",
   "fix imports", "type of this symbol") and assert each
   intended LSP tool appears in the top 5 matches.
3. Assert the LSP refactor suite is in scope (in the agent's
   tool roster) when `CodingMode` is active AND an LSP session
   exists — without needing `find_capability` first.
4. Negative: same `Sigil` but with `ConversationMode` active
   instead of `CodingMode`. Assert the refactor suite is NOT
   auto-included — non-coding work doesn't carry the heavier
   roster.

### Related

- Bug #184 — tool descriptions reference other tools. The
  refactor suite's descriptions should be audited at the same
  time as the keyword pass — they likely have cross-references
  to `lsp_did_change` etc. that should be stripped per #184.
- Bug #185 — LSP auto-sync. Refactor tools that produce edits
  need the same sync hook. Once #185 lands, refactor tools
  emit `touchesFile = true` results and the listener handles
  LSP state automatically; agent invokes one refactor tool,
  diagnostics see the result.
- Bug #186 — position-based edit primitive. Refactor tools
  (lsp_rename, lsp_apply_code_action) internally produce
  `WorkspaceEdit` payloads; `edit_at_range` is the natural
  underlying primitive for applying them.
- Bug #183 — `edit_file` silent failure. Even with the
  refactor suite reaching the agent, `edit_file` still needs a
  fix for the cases where the agent legitimately wants
  string-based replacement.
