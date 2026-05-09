#!/usr/bin/env bash
# Test fixture pretending to be a Metals binary that crashes early
# without writing `.metals/mcp.json`. Bug #68 — used to verify that
# `MetalsManager.waitForReady` surfaces a clear diagnostic (exit
# code + recent stdout tail) instead of waiting forever.
set -euo pipefail
# stderr — that's where real Metals writes fatal errors and
# what `MetalsManager.startStderrDrainer` captures into the
# tail buffer for the failure diagnostic. With
# `redirectErrorStream(false)` (required so the LSP wire on
# stdout stays parseable), stdout messages would be consumed
# by the lsp4j launcher and never make it to the diagnostic.
echo "[bloop] starting up" >&2
echo "[bloop] resolving dependencies" >&2
echo "[bloop] FATAL: simulated crash" >&2
exit 7
