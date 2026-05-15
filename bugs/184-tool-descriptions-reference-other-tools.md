# ❌ #184 — Tool descriptions reference other tool names, creating broken cross-references when consumers expose only a subset

**Where:**
- `core/src/main/scala/sigil/tool/.../` — every tool's
  `description` string. Specific violations confirmed today:
  - `lsp_did_change` (tooling-sigil): references `edit_file`,
    `write_file`, `lsp_diagnostics`, `lsp_completion`.
  - Likely others across the LSP/BSP/filesystem tool families —
    needs a systematic audit.

**What's wrong:**

Apps embedding Sigil pick which tools to expose to the agent. The
framework ships a large catalog (filesystem, LSP, BSP, web,
slack, …) and any given consumer might expose a subset based on
its needs. Sage today exposes both `edit_file` and `lsp_did_change`,
but it's reasonable for a different consumer to expose `edit_file`
without `lsp_did_change` (no LSP integration), or vice versa, or
neither (a chat-only app), etc.

Tool descriptions that reference other tools by name break in two
ways under this model:

1. **Agent confusion.** The agent reading
   `lsp_did_change`'s description sees "Use AFTER editing a file
   (via `edit_file` / `write_file`)" — and if those tools aren't
   in the running app's roster, the description is asking the
   agent to compose against tools that don't exist. Some models
   will try to invoke them anyway (fabricated tool name → tool
   dispatch refuses); others will get confused about whether the
   tool is usable at all.

2. **Conceptual coupling that isn't real.** The description
   implies `lsp_did_change` *depends on* `edit_file` /
   `write_file`. It doesn't — it's a standalone "tell the LSP
   server about new content" operation. A consumer with their own
   filesystem-edit primitive (or a remote-edit workflow, or even
   no filesystem layer at all) should be able to use
   `lsp_did_change` cleanly. The cross-reference inverts the
   architecture: instead of self-describing operations, tools
   describe themselves *as adjuncts to other tools*.

### Concrete violation that surfaced this bug

`lsp_did_change`'s current description (truncated to the
violating sentence):

> "Use AFTER editing a file (via `edit_file` / `write_file`) so
> subsequent `lsp_diagnostics` / `lsp_completion` see the new
> content."

Four sibling tool names baked into one description. None of them
are tightly coupled to `lsp_did_change` — they're separate tools
in the catalog that happen to compose well in a typical workflow.

### Suggested fix

**Audit + rewrite every tool description to describe what THIS
tool does on its own terms.** Strip cross-references except in
the tightly-coupled cases:

**Allowed cross-references (tightly coupled — these ship as
inseparable families):**
- `pin_complexity` ↔ `unpin_complexity`
- `respond` ↔ `respond_options` ↔ `respond_field` (the respond
  family)
- `record_consent` referencing the *concept* of consent-gated
  tools (but not naming specific ones)
- `change_mode` referencing the *concept* of modes (but not
  naming specific modes — those are dynamic per app)

**Not allowed (loosely coupled — composable but not bound):**
- `lsp_did_change` referencing `edit_file` / `write_file` /
  `lsp_diagnostics` / `lsp_completion`. These are all
  independently registered tools that an app may or may not
  expose. The right description shape for `lsp_did_change`:

  > "Update the LSP server's in-memory copy of a document by
  > passing the file's complete new contents. The LSP server's
  > diagnostic and completion computations operate against this
  > in-memory copy until the next change is sent or the document
  > is closed. Use after any external mutation of the document
  > whose effects you want the LSP to see."

  No tool names referenced. The description tells the agent what
  the tool does and when to invoke it; *composing* it with edit
  / diagnostics tools is a workflow concern the agent figures
  out from its own roster and the user's task — not from a
  hardcoded reference.

**Mode descriptions, skills, and system prompts are the right
place for workflow guidance.** "When making code edits, prefer
the safe-edit workflow: edit → notify LSP → check diagnostics"
belongs in a `coding` mode description or a skill, not embedded
in `lsp_did_change`'s description. That way:
- The mode/skill is what knows which tools are in scope.
- Individual tool descriptions stay self-contained.
- Apps that don't expose the full coding workflow can omit the
  mode/skill without confusing tool descriptions left over.

### Test sketch

Under `core/src/test/scala/sigil/tool/`:

1. Inventory: build a fixture that loads every shipped
   `TypedTool` and reads its `description`.
2. For each description, search for tool-name references using
   either:
   - A registry of known tool names (cross-reference against the
     `Sigil.staticTools` enumeration of canonical tools).
   - Regex hints like backtick-wrapped lowercase-underscore
     identifiers (`` `<name>` `` patterns) — common in Sigil
     descriptions today.
3. Assert: every cross-reference is to a "tightly coupled" tool
   in the allowed list above. Anything else fails the test.
4. Optional: emit a report of all cross-references so the audit
   is visible in CI even when adding new tools that include them.

### Related

- Bug #183 — `edit_file` silent failure. Same area surface
  (filesystem edit + LSP integration). #184 is the descriptions
  side; #183 is the tool-implementation side. Fixing both
  together produces a coherent edit story.
- Bug #181 / #182 — framework state leaking into agent context.
  Different layer (Tool-role Messages vs. tool descriptions),
  same architectural concern: keep what the agent reads about
  the framework precisely what's true for THIS conversation /
  THIS roster — no references to other tools / framework
  internals that may not be in scope.
