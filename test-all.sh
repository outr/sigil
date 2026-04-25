#!/usr/bin/env bash
# Run every sigil test, including the paid live-provider specs gated
# behind SIGIL_LIVE (see core/src/test/scala/spec/LiveProbe.scala).

set -euo pipefail
cd "$(dirname "$0")"
exec env SIGIL_LIVE=1 sbt test
