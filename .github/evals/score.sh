#!/usr/bin/env bash
set -euo pipefail

results_file="${1:-.github/evals/results-template.csv}"

if [[ ! -f "$results_file" ]]; then
  echo "Results file not found: $results_file" >&2
  exit 1
fi

total=0
pass=0
partial=0
fail=0
unknown=0

while IFS=, read -r task_id status _primary_agent _notes; do
  if [[ "$task_id" == "task_id" ]]; then
    continue
  fi
  total=$((total + 1))
  case "${status}" in
    pass) pass=$((pass + 1)) ;;
    partial) partial=$((partial + 1)) ;;
    fail) fail=$((fail + 1)) ;;
    *) unknown=$((unknown + 1)) ;;
  esac
done < "$results_file"

score=$((pass * 10 + partial * 5))
max=$((total * 10))

printf 'Eval Results\n'
printf '%s\n' '------------'
printf 'Total: %d\n' "$total"
printf 'Pass: %d\n' "$pass"
printf 'Partial: %d\n' "$partial"
printf 'Fail: %d\n' "$fail"
printf 'Unknown: %d\n' "$unknown"
printf 'Score: %d/%d\n' "$score" "$max"

if [[ "$total" -gt 0 ]]; then
  pct=$((score * 100 / max))
  printf 'Percent: %d%%\n' "$pct"
fi
