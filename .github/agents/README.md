# Custom Agents

Slim by design. Most "agents" in this repo are now skills under
`.github/skills/`. Only one agent remains:

- **spec-guard** — full spec and architecture compliance audit before or
  after implementation. Use this as a heavyweight cross-cutting review;
  for narrower workflows pick the matching skill instead.

Routing matrix lives in [`AGENTS.md`](../../AGENTS.md#task--skill--agent-routing-matrix).

Structured reporting:

- Specialist agents should emit JSON compatible with `.github/tooling/agent-report.schema.json`.
- Use `.github/tooling/agent-report.template.json` as a starting point.
