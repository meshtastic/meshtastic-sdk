#!/usr/bin/env bash
set -euo pipefail

# Run lightweight guardrail checks for agent tooling files.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required command: $cmd" >&2
    return 1
  fi
}

require_cmd shellcheck
require_cmd jq
require_cmd yq
require_cmd markdownlint
require_cmd ajv

if [[ -d .github/workflows ]] && compgen -G ".github/workflows/*.yml" >/dev/null; then
  require_cmd actionlint
fi

echo "[tooling] Validating JSON files"
if compgen -G ".github/**/*.json" >/dev/null; then
  find .github -type f -name "*.json" -print0 | xargs -0 -n1 jq -e . >/dev/null
fi

echo "[tooling] Validating prompt and agent frontmatter"
for f in .github/prompts/*.prompt.md .github/agents/*.agent.md; do
  [[ -f "$f" ]] || continue
  frontmatter="$(awk 'BEGIN{inblock=0;count=0} /^---[[:space:]]*$/ {count++; if (count==1){inblock=1; next} if (count==2){inblock=0; exit}} inblock{print}' "$f")"
  if [[ -z "$frontmatter" ]]; then
    echo "Missing or invalid frontmatter block in $f" >&2
    exit 1
  fi
  # Validate extracted YAML and required keys.
  printf '%s\n' "$frontmatter" | yq -o=json '.' >/dev/null
  printf '%s\n' "$frontmatter" | yq -e 'has("description")' >/dev/null
done

echo "[tooling] Validating schema and template relationship"
ajv validate --spec=draft2019 -s .github/tooling/agent-report.schema.json -d .github/tooling/agent-report.template.json >/dev/null

echo "[tooling] Linting shell scripts"
find .github -type f -name "*.sh" -print0 | xargs -0 shellcheck

echo "[tooling] Linting markdown for .github"
markdownlint ".github/**/*.md" --disable MD013 MD022 MD032 MD041 MD060 MD033

if [[ -d .github/workflows ]] && compgen -G ".github/workflows/*.yml" >/dev/null; then
  echo "[tooling] Linting GitHub workflows"
  if [[ -d .git ]]; then
    actionlint
  else
    echo "[tooling] Skipping actionlint (no .git directory present in this environment)"
  fi
  echo "[tooling] Verifying every action reference is SHA-pinned"
  # Allowed: 40-hex SHA refs, plus local actions (`./…`) and reusable workflows
  # in this repo. Disallow `@v4`, `@main`, `@latest`, etc.
  bad=$(grep -rEn '^[[:space:]]*-?[[:space:]]*uses:[[:space:]]*' .github/workflows \
    | grep -vE 'uses:[[:space:]]*\./|uses:[[:space:]]*[^@]+@[0-9a-f]{40}([[:space:]]|$|#)' \
    || true)
  if [[ -n "$bad" ]]; then
    echo "Action references must be SHA-pinned (use '@<40-hex-sha> # vX.Y.Z'):" >&2
    printf '%s\n' "$bad" >&2
    exit 1
  fi
fi

echo "[tooling] Running eval scorer smoke check"
bash .github/evals/score.sh .github/evals/results-template.csv >/dev/null

echo "[tooling] All checks passed"
