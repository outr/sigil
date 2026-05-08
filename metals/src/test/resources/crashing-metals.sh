#!/usr/bin/env bash
# Test fixture pretending to be a Metals binary that crashes early
# without writing `.metals/mcp.json`. Bug #68 — used to verify that
# `MetalsManager.waitForReady` surfaces a clear diagnostic (exit
# code + recent stdout tail) instead of waiting forever.
set -euo pipefail
echo "[bloop] starting up"
echo "[bloop] resolving dependencies"
echo "[bloop] FATAL: simulated crash"
exit 7
