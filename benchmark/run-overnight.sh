#!/usr/bin/env bash
# benchmark/run-overnight.sh
#
# Runs every implemented sigil benchmark in sequence. Each run writes:
#   - benchmark/results/overnight-<timestamp>/<bench>.md   — markdown report
#   - benchmark/results/overnight-<timestamp>/<bench>.log  — full stdout/stderr
#
# Each bench runs in its own forked sbt process so a crash in one
# doesn't kill the rest. The script logs the start/finish timestamp
# of every bench so you can see at a glance which ones completed.
#
# Datasets:
#   - Cached on HuggingFace (auto-resolved):  LongMemEval, ConvoMem, LoCoMo
#   - Bundled with sigil (no external data):  AgentDojo banking
#   - Need explicit download (see RUNNER_OVERNIGHT_README.md):
#       BFCL        → /tmp/bfcl-data
#       MemBench    → /tmp/membench-data
#       REALTALK    → /tmp/realtalk-data
#   Missing-dataset benches are skipped with a clear message; the
#   final summary lists what ran and what was skipped.
#
# Env vars (not set here — caller's shell):
#   OPENAI_API_KEY, ANTHROPIC_API_KEY, GOOGLE_API_KEY, SIGIL_LLAMACPP_HOST

set -uo pipefail

cd "$(dirname "$0")/.."

ts=$(date +%Y-%m-%d_%H%M%S)
outdir="benchmark/results/overnight-$ts"
mkdir -p "$outdir"
summary="$outdir/SUMMARY.md"

# Resolve cached HF dataset paths. The snapshot dir is a hash; pick
# the most recent one if multiple exist.
hf_snapshot() {
  local repo=$1
  local snapshot_dir="$HOME/.cache/huggingface/hub/datasets--$repo/snapshots"
  if [ -d "$snapshot_dir" ]; then
    ls -1t "$snapshot_dir" 2>/dev/null | head -1 | awk -v p="$snapshot_dir" '{print p"/"$1}'
  fi
}

LONGMEMEVAL_SNAP=$(hf_snapshot "xiaowu0162--longmemeval-cleaned")
CONVOMEM_SNAP=$(hf_snapshot "Salesforce--ConvoMem")

LONGMEMEVAL_FILE=""
[ -n "$LONGMEMEVAL_SNAP" ] && LONGMEMEVAL_FILE="$LONGMEMEVAL_SNAP/longmemeval_s_cleaned.json"

CONVOMEM_DIR=""
LOCOMO_DIR=""
if [ -n "$CONVOMEM_SNAP" ]; then
  CONVOMEM_DIR="$CONVOMEM_SNAP/core_benchmark/pre_mixed_testcases"
  LOCOMO_DIR="$CONVOMEM_SNAP/legacy_benchmarks/locomo"
fi

BFCL_DIR="/tmp/bfcl-data"
MEMBENCH_DIR="/tmp/membench-data"
REALTALK_DIR="/tmp/realtalk-data"

started=()
ok=()
failed=()
skipped=()

run_bench() {
  local label=$1
  local report=$2
  local logfile=$3
  shift 3
  local args=("$@")

  echo
  echo "=== $label ==="
  echo "[$(date +%H:%M:%S)] starting $label"
  started+=("$label")

  if sbt "${args[@]}" 2>&1 | tee "$logfile"; then
    if grep -qE '^\[error\]|FATAL|sys.exit\(1\)|sys\.exit\(2\)' "$logfile"; then
      echo "[$(date +%H:%M:%S)] FAILED $label (errors in log)"
      failed+=("$label")
    else
      echo "[$(date +%H:%M:%S)] DONE $label"
      ok+=("$label")
    fi
  else
    echo "[$(date +%H:%M:%S)] FAILED $label (sbt exited nonzero)"
    failed+=("$label")
  fi
}

skip_bench() {
  local label=$1
  local reason=$2
  echo
  echo "=== $label === SKIPPED: $reason"
  skipped+=("$label — $reason")
}

# --- Memory retrieval ---

if [ -f "$LONGMEMEVAL_FILE" ]; then
  run_bench "LongMemEval" \
    "$outdir/longmemeval.md" \
    "$outdir/longmemeval.log" \
    "benchmark/runMain bench.LongMemEvalBench $LONGMEMEVAL_FILE --report $outdir/longmemeval.md"
else
  skip_bench "LongMemEval" "missing $LONGMEMEVAL_FILE (HF cache: xiaowu0162/longmemeval-cleaned)"
fi

if [ -d "$LOCOMO_DIR" ]; then
  run_bench "LoCoMo" \
    "$outdir/locomo.md" \
    "$outdir/locomo.log" \
    "benchmark/runMain bench.LoCoMoBench $LOCOMO_DIR --report $outdir/locomo.md"
else
  skip_bench "LoCoMo" "missing $LOCOMO_DIR (HF cache: Salesforce/ConvoMem → legacy_benchmarks/locomo)"
fi

if [ -d "$CONVOMEM_DIR" ]; then
  run_bench "ConvoMem" \
    "$outdir/convomem.md" \
    "$outdir/convomem.log" \
    "benchmark/runMain bench.ConvoMemBench $CONVOMEM_DIR --max-questions 5000 --report $outdir/convomem.md"
else
  skip_bench "ConvoMem" "missing $CONVOMEM_DIR (HF cache: Salesforce/ConvoMem → core_benchmark/pre_mixed_testcases)"
fi

if [ -d "$MEMBENCH_DIR" ]; then
  run_bench "MemBench" \
    "$outdir/membench.md" \
    "$outdir/membench.log" \
    "benchmark/runMain bench.MemBenchBench $MEMBENCH_DIR --report $outdir/membench.md"
else
  skip_bench "MemBench" "missing $MEMBENCH_DIR (clone github.com/import-myself/Membench → MemData/)"
fi

if [ -d "$REALTALK_DIR" ]; then
  run_bench "REALTALK" \
    "$outdir/realtalk.md" \
    "$outdir/realtalk.log" \
    "benchmark/runMain bench.RealTalkBench $REALTALK_DIR --report $outdir/realtalk.md"
else
  skip_bench "REALTALK" "missing $REALTALK_DIR (clone github.com/danny911kr/REALTALK → data/)"
fi

# --- Tool use ---

if [ -d "$BFCL_DIR" ]; then
  # Models match previous runs in scores.md so the overnight is an
  # apples-to-apples regression check.
  for model in "openai/gpt-5.4" "openai/gpt-4o-mini" "llamacpp/qwen3.5-9b-q4_k_m"; do
    label_safe="bfcl-$(echo "$model" | tr '/' '_')"
    run_bench "BFCL ($model)" \
      "$outdir/$label_safe.md" \
      "$outdir/$label_safe.log" \
      "benchmark/runMain bench.BFCLBench $BFCL_DIR --model $model --report $outdir/$label_safe.md"
  done
else
  skip_bench "BFCL" "missing $BFCL_DIR (download from gorilla/berkeley-function-call-leaderboard/bfcl_eval/data)"
fi

# --- Agent-loop safety ---
# Models match previous runs in scores.md.
for model in "openai/gpt-5.4-mini" "anthropic/claude-haiku-4-5" "llamacpp/qwen3.5-9b-q4_k_m"; do
  label_safe="agentdojo-banking-$(echo "$model" | tr '/' '_')"
  run_bench "AgentDojo banking ($model)" \
    "$outdir/$label_safe.md" \
    "$outdir/$label_safe.log" \
    "benchmark/runMain bench.agentdojo.banking.AgentDojoBankingBench $model"
done

# --- Summary ---

{
  echo "# Overnight Benchmark Run — $ts"
  echo
  echo "**Started:** $(stat -c '%y' "$outdir" 2>/dev/null | cut -d. -f1)"
  echo "**Ended:** $(date '+%Y-%m-%d %H:%M:%S')"
  echo "**Reports:** \`$outdir/\`"
  echo
  echo "## Completed (${#ok[@]})"
  for x in "${ok[@]}"; do echo "- $x"; done
  echo
  echo "## Failed (${#failed[@]})"
  for x in "${failed[@]}"; do echo "- $x"; done
  echo
  echo "## Skipped — missing dataset (${#skipped[@]})"
  for x in "${skipped[@]}"; do echo "- $x"; done
  echo
  echo "## Compare to baseline"
  echo
  echo "Baseline: \`benchmark/results/scores-baseline-$(date +%Y-%m-%d).md\`"
  echo
  echo "Spot-check the headline numbers in each report's \`## Summary\` section against the baseline; any drop > 1pp is worth investigating."
} > "$summary"

echo
echo "============================="
echo "Overnight run complete."
echo "Summary: $summary"
echo "Completed: ${#ok[@]} | Failed: ${#failed[@]} | Skipped: ${#skipped[@]}"
echo "============================="
