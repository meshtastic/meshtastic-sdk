# Agent Evals

This directory provides a minimal, repeatable eval harness for custom agent tooling.

Files:
- [canonical-tasks.md](canonical-tasks.md): 10 reference tasks.
- [results-template.csv](results-template.csv): fill statuses as pass, partial, or fail.
- [score.sh](score.sh): computes score and percentage.

Usage:
1. Copy results template if needed.
2. Record outcomes for each task.
3. Run:
   - `bash .github/evals/score.sh .github/evals/results-template.csv`

Scoring:
- pass = 10 points
- partial = 5 points
- fail/unknown = 0 points

This harness is intentionally lightweight and can be replaced later with CI-integrated eval automation.
