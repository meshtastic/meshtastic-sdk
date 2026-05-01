# Tooling Checks

This directory contains shared validation artifacts and scripts for agent tooling.

## Files
- `check.sh`: runs guardrail checks for `.github` automation files.
- `agent-report.schema.json`: schema for structured specialist-agent output.
- `agent-report.template.json`: starter payload matching the schema.

## Local Usage
Run all tooling checks:

```bash
bash .github/tooling/check.sh
```

Enable local pre-commit hook checks:

```bash
git config core.hooksPath .githooks
```

Then commits will run:

```bash
bash .github/tooling/check.sh
```
