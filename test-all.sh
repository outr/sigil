#!/usr/bin/env bash
# Run every sigil test, including:
#   - paid live-provider specs gated behind SIGIL_LIVE
#   - multi-minute slow specs gated behind SIGIL_SLOW
# (see core/src/test/scala/spec/LiveProbe.scala for both gates).

set -euo pipefail
cd "$(dirname "$0")"
exec env SIGIL_LIVE=1 SIGIL_SLOW=1 sbt 'clean; Test/compile; test'
