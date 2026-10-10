# Custom Agents

Slim by design. Most "agents" in this repo are now skills under
`.github/skills/`. Only one agent remains:

- **spec-guard** — full spec and architecture compliance audit before or
  after implementation. Use this as a heavyweight cross-cutting review;
  for narrower workflows pick the matching skill instead.

  It ships in two files because the two loaders read different directories:
  this one (`spec-guard.agent.md`) for GitHub Copilot, and
  `.claude/agents/spec-guard.md` for Claude Code. Keep their bodies identical.

Routing matrix lives in [`AGENTS.md`](../../AGENTS.md#task--skill--agent-routing-matrix).

Structured reporting:

- Specialist agents should emit JSON compatible with `.github/tooling/agent-report.schema.json`.
- Use `.github/tooling/agent-report.template.json` as a starting point.
