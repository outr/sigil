#!/usr/bin/env bash
# Test fixture pretending to be the Metals binary. Writes the
# rendezvous file `.metals/mcp.json` with the port supplied via
# the env var `FAKE_METALS_PORT` (or 54321 by default), then sleeps
# until killed. Lets the spec exercise spawn → rendezvous-detected →
# port-update → idle-reap → shutdown without a real Metals install.
set -euo pipefail
mkdir -p .metals
PORT="${FAKE_METALS_PORT:-54321}"
echo "{\"port\": ${PORT}}" > .metals/mcp.json
# Stay alive until the parent kills us. Sleep loop (vs. a single
# long sleep) so the SIGTERM from `Process.destroy()` lands quickly.
while true; do sleep 0.5; done
