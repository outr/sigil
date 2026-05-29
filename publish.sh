#!/usr/bin/env bash

set -euo pipefail
./test-all.sh || { echo "tests failed — aborting publish" >&2; exit 1; }
sbt publish
