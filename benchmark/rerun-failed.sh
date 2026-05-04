#!/usr/bin/env bash
# benchmark/rerun-failed.sh
#
# Re-runs only the 8 benchmark entries that failed in the previous
# overnight (the memory benchmarks + BFCL × 3 models). The 3
# AgentDojo runs that completed under the orchestrator's
# parallel-tool-call bug are NOT re-run by this script — see
# `run-overnight.sh` if you want a fresh full sweep.
#
# Reuses the runner's path-resolution logic; outputs to a new
# overnight-<timestamp>/ directory so the original logs stay intact
# for diff/audit.

set -uo pipefail

cd "$(dirname "$0")/.."

export SIGIL_LLAMACPP_HOST="${SIGIL_LLAMACPP_HOST:-http://localhost:8081}"
echo "Using SIGIL_LLAMACPP_HOST=$SIGIL_LLAMACPP_HOST"

if ! command -v sbt >/dev/null 2>&1; then
  if [ -x "$HOME/.sdkman/candidates/sbt/current/bin/sbt" ]; then
    export PATH="$HOME/.sdkman/candidates/sbt/current/bin:$PATH"
  elif [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    # shellcheck disable=SC1091
    source "$HOME/.sdkman/bin/sdkman-init.sh"
  else
    echo "ERROR: sbt not found on PATH and no SDKMAN install detected" >&2
    exit 1
  fi
fi

ts=$(date +%Y-%m-%d_%H%M%S)
outdir="$(pwd)/benchmark/results/overnight-$ts-rerun"
mkdir -p "$outdir"

hf_snapshot() {
  local repo=$1
  local snapshot_dir="$HOME/.cache/huggingface/hub/datasets--$repo/snapshots"
  if [ -d "$snapshot_dir" ]; then
    ls -1t "$snapshot_dir" 2>/dev/null | head -1 | awk -v p="$snapshot_dir" '{print p"/"$1}'
  fi
}

LONGMEMEVAL_FILE="$(hf_snapshot xiaowu0162--longmemeval-cleaned)/longmemeval_s_cleaned.json"
CONVOMEM_DIR="$(hf_snapshot Salesforce--ConvoMem)/core_benchmark/pre_mixed_testcases"
LOCOMO_DIR="$(hf_snapshot Salesforce--ConvoMem)/legacy_benchmarks/locomo"
BFCL_DIR="/tmp/bfcl-data"
MEMBENCH_DIR="/tmp/membench-data"
REALTALK_DIR="/tmp/realtalk-data"

ok=()
failed=()

run_bench() {
  local label=$1
  shift
  local args=("$@")
  echo
  echo "=== $label ==="
  echo "[$(date +%H:%M:%S)] starting $label"
  local logfile=$(echo "$label" | tr ' ()/' '___' | sed 's/__\+/_/g' | sed 's/_*$//')
  logfile="$outdir/$logfile.log"
  if sbt "${args[@]}" 2>&1 | tee "$logfile"; then
    if grep -qE 'FATAL|sys.exit\(1\)|sys\.exit\(2\)|^\[error\]' "$logfile" \
       && ! grep -q '^\[success\]' "$logfile"; then
      echo "[$(date +%H:%M:%S)] FAILED $label"
      failed+=("$label")
    else
      echo "[$(date +%H:%M:%S)] DONE $label"
      ok+=("$label")
    fi
  else
    echo "[$(date +%H:%M:%S)] FAILED $label (sbt nonzero)"
    failed+=("$label")
  fi
}

# --- The 8 previously-failed benchmarks ---

run_bench "LongMemEval" \
  "benchmark/runMain bench.LongMemEvalBench $LONGMEMEVAL_FILE --report $outdir/longmemeval.md"

run_bench "LoCoMo" \
  "benchmark/runMain bench.LoCoMoBench $LOCOMO_DIR --report $outdir/locomo.md"

run_bench "ConvoMem" \
  "benchmark/runMain bench.ConvoMemBench $CONVOMEM_DIR --max-questions 5000 --report $outdir/convomem.md"

run_bench "MemBench" \
  "benchmark/runMain bench.MemBenchBench $MEMBENCH_DIR --report $outdir/membench.md"

run_bench "REALTALK" \
  "benchmark/runMain bench.RealTalkBench $REALTALK_DIR --report $outdir/realtalk.md"

for model in "openai/gpt-5.4" "openai/gpt-4o-mini" "llamacpp/gemma-4-26b"; do
  label_safe=$(echo "$model" | tr '/' '_')
  run_bench "BFCL ($model)" \
    "benchmark/runMain bench.BFCLBench $BFCL_DIR --model $model --report $outdir/bfcl-$label_safe.md"
done

{
  echo "# Rerun of failed benchmarks — $ts"
  echo
  echo "**Reports:** \`$outdir/\`"
  echo
  echo "## Completed (${#ok[@]})"
  for x in "${ok[@]}"; do echo "- $x"; done
  echo
  echo "## Failed (${#failed[@]})"
  for x in "${failed[@]}"; do echo "- $x"; done
} > "$outdir/SUMMARY.md"

echo
echo "Rerun complete."
echo "Summary: $outdir/SUMMARY.md"
echo "Completed: ${#ok[@]} | Failed: ${#failed[@]}"
