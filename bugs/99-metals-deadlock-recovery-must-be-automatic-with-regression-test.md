# ❌ #99 — Metals deadlock (duplicate processes + poisoned H2 status) must self-heal automatically; needs regression test that reproduces the exact failure mode

**Where:**
`metals/src/main/scala/sigil/metals/MetalsManager.scala`
(`ensureRunning` + `spawnAndRegister` + Metals-startup hooks)
and `metals/src/test/scala/sigil/metals/...` (new test).

**What's wrong:** Bug #94's atomic-computeIfAbsent fix wasn't
sufficient. The deadlock the user hit yesterday recurred today,
identical shape:

```
PID 3871175  started 09:22:49  metals.log: "Started: Metals 1.6.7"
PID 3872422  started 09:23:22  metals.log: "Started: Metals 1.6.7"
                                ↑ both then logged "skipping build
                                  import with status 'Started'"
```

Two contributing failures, both still happening despite #94's
ship:

### Failure 1: Duplicate processes per workspace

#94's `computeIfAbsent` placeholder protects same-key
concurrent calls. But the deadlock recurs when:

  - Two different code paths spawn against the same workspace
    (e.g. the MCP path via `start_metals` AND the generic LSP
    path via `LspServerConfig` from #88), each with its own
    key-computation that doesn't collide; or
  - The placeholder is removed/cleared before the spawn
    completes, opening a second-spawn window; or
  - A retry path doesn't go through `ensureRunning`'s atomic
    entry.

The empirical evidence — two PIDs spawned 33s apart for the
same workspace — proves the protection isn't covering all
paths.

### Failure 2: Poisoned H2 db status persists across restarts

Metals persists build-import status in
`<workspace>/.metals/metals.mv.db`. When a Metals process is
killed mid-import (SIGKILL, OOM, machine reboot, user
manually killing duplicates), the status row stays at
`Started`. Every subsequent Metals startup reads this status,
sees `Started`, and logs:

```
INFO  skipping build import with status 'Started'
```

…then sits idle. **Forever.** No automatic detection that the
peer who was importing is dead. The `.bloop/` never gets
generated, every `lsp_*` / `bsp_*` tool that depends on it
hangs.

The recovery today is manual: kill all Metals java processes,
wipe `metals.mv.db`, restart Metals. That's not a workflow we
can ask users to discover. The framework should self-heal.

**Suggested fix:** Two parts, both required:

### 1. Self-healing on startup

In `MetalsManager` (or wherever Metals is launched), before
treating an existing `Started` status as authoritative,
verify a peer process is actually alive:

```scala
def reconcileImportStatus(workspace: Path): Task[Unit] = Task {
  // Read the H2 db's current import status
  val status = readImportStatus(workspace)
  if (status == Status.Started) {
    val peerAlive = isAnyMetalsProcessAliveFor(workspace)
    if (!peerAlive) {
      // Last importer died without finishing. Reset to Pending
      // so the new instance retries instead of deferring to a
      // ghost.
      resetImportStatus(workspace, Status.Pending)
      log.warn(s"Reset stale Metals import status for $workspace " +
               s"(no peer process alive — last run probably crashed).")
    }
  }
}
```

Run this in the `spawnAndRegister` path BEFORE the new Metals
instance starts reading its H2 db, so the new instance sees a
clean status and proceeds with a fresh import.

`isAnyMetalsProcessAliveFor(workspace)` walks the JVM's known
running processes (or just checks the manager's own `entries`
map after the placeholder protection from #94 — if no
`Entry.process.isAlive` exists for this workspace, no live
peer, status is stale).

### 2. Tighter duplicate-process protection

Audit every code path that can call `ensureRunning` (or
spawn Metals via any other means). Funnel ALL of them through
`ensureRunning` — no parallel paths. Specifically:

  - The MCP path (`start_metals` tool)
  - The LSP path (when bug #88's `LspServerConfig` first
    attaches a session)
  - Any retry / health-check path

All must use `ensureRunning` with the same key derivation.
The placeholder Entry from #94 stays put through the entire
spawn lifecycle, only removed on stop or process death.

### 3. **Regression test that reproduces the exact failure**

This is the user's explicit request — and the right one.
Add a test under
`metals/src/test/scala/sigil/metals/MetalsDeadlockRegressionSpec.scala`:

```scala
class MetalsDeadlockRegressionSpec extends AbstractMetalsSpec {

  test("ensureRunning recovers from poisoned H2 status (peer died mid-import)") {
    // 1. Start Metals against a fresh workspace
    val mm = newManager()
    val workspace = freshScalaWorkspace()
    val name1 = mm.ensureRunning(workspace).sync()

    // 2. Simulate import-in-progress: write status=Started to H2 directly
    setH2ImportStatus(workspace, "Started")

    // 3. SIGKILL the Metals process (simulates crash mid-import)
    killProcessFor(mm, workspace)

    // 4. Restart: ensureRunning must detect the dead peer, reset
    //    status, and complete a fresh import
    val name2 = mm.ensureRunning(workspace).sync()
    eventually(60.seconds) {
      assert(workspace.resolve(".bloop").toFile.exists,
        ".bloop/ should exist after recovery import completes")
    }
  }

  test("ensureRunning never spawns duplicate processes for same workspace") {
    val mm = newManager()
    val workspace = freshScalaWorkspace()

    // Concurrent calls from N threads — placeholder + atomic
    // computeIfAbsent must funnel all to one spawn
    val results = (1 to 10).par.map(_ => mm.ensureRunning(workspace).sync()).toList
    assert(results.distinct.size == 1, "all callers must receive the same server name")

    // Verify exactly one Metals java process alive
    val processes = listMetalsProcessesFor(workspace)
    assert(processes.size == 1, s"expected 1 Metals process, got ${processes.size}")
  }

  test("ensureRunning from MCP and LSP code paths share the same instance") {
    val mm = newManager()
    val workspace = freshScalaWorkspace()

    // Simulate the two code paths bug #88 introduced:
    val nameFromMcp = mm.ensureRunning(workspace).sync()              // start_metals path
    val nameFromLsp = mm.ensureRunningViaLsp(workspace).sync()        // generic LSP path

    assert(nameFromMcp == nameFromLsp,
      "MCP and LSP entry points must converge on the same Metals instance")

    val processes = listMetalsProcessesFor(workspace)
    assert(processes.size == 1, s"expected 1 Metals process, got ${processes.size}")
  }
}
```

This test (a) reproduces the exact failure the user hit, (b)
locks the regression — once it passes, any future change that
re-introduces the duplicate-spawn or poisoned-status path will
fail this spec.

The test is the contract. If we can write the test, we can
verify the fix; if the fix breaks, the test catches it next
release. Without the test, we'll be debugging the same dead
processes again.
