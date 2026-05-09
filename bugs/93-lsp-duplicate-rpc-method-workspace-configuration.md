# ❌ #93 — `LSP error: Duplicate RPC method workspace/configuration` after #88's `start_metals` config upsert

**Where:** Likely `tooling/src/main/scala/sigil/tooling/LspManager.scala`
or `LspSession.scala`, where the LSP4J server proxy is
constructed via `Launcher.createIoLauncher` (or equivalent) with
JSON-RPC handler annotations.

**What's wrong:** After bug #88 landed (so `start_metals`
upserts `LspServerConfig("scala", ...)` and the generic `lsp_*`
family can target Metals), the agent eventually calls
`lsp_workspace_symbols`. The framework spawns an LSP session
against the persisted config and immediately dies with:

```
LSP error: Duplicate RPC method workspace/configuration
```

LSP4J's `GenericEndpoint` rejects the launcher when two methods
on the local interface are both annotated `@JsonRequest("workspace/configuration")`
(or the same method name appears on two interfaces being
multiplexed into one `Launcher`). The error fires at launcher
construction, before the LSP handshake even starts — so the
language server never connects.

Two plausible introductions in #88's path:

  1. **Two `LspClient` interfaces multiplexed.** When
     `start_metals` runs Metals via the existing MCP path AND
     the generic `lsp_*` path connects via the new
     `LspServerConfig`, they may share an `LspClient` interface
     that has `workspace/configuration` declared twice (once
     for each integration's client surface).

  2. **Re-registration on idempotent `start_metals`.** The
     comment on `StartMetalsTool` says "Idempotent — calling
     against an already-running workspace just touches the
     last-used clock." But if the new config-upsert path also
     re-runs the LSP4J launcher construction on the second
     call, the second launcher's introspection finds duplicate
     handler annotations from the first launcher's still-live
     proxy.

**Suggested fix:** Identify which interface(s) declare
`workspace/configuration`, ensure exactly one declaration in
the launcher's local-services list, and guard `start_metals`'s
LspServerConfig path so it doesn't re-run launcher
construction when an LSP session for that languageId is already
attached.

Concrete checks:

  - Grep `tooling/src/main/scala` for `@JsonRequest("workspace/configuration")`
    and `@JsonNotification("workspace/configuration")`. There
    should be exactly one of each, on a single interface, and
    only that interface should be passed as `localServices` to
    `Launcher.createIoLauncher`.
  - Add a `LspManager.sessionFor(languageId).isDefined` check
    before re-launching; idempotent calls should reuse the
    existing session, not construct a fresh launcher.

If the duplicate is genuinely from `MetalsSigil` and `ToolingSigil`
both contributing handlers to the same launcher, the fix is to
unify them: one launcher per LspServerConfig, one local-services
interface, period.

Live trace: agent eventually called `lsp_workspace_symbols`
(rank 1 in find_capability for "lsp workspace symbols admin"),
got the duplicate-method error, the LSP path is now permanently
broken for the conversation. Stop-gap is `stop_metals` +
re-`start_metals` but that's not a workflow we should expect
users to discover.
