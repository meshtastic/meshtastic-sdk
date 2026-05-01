# Canonical Agent Eval Tasks

Use these 10 tasks to evaluate routing quality, policy compliance, and specialist output quality.

| ID | Domain | Prompt | Expected Primary Agent | Pass Criteria |
|---|---|---|---|---|
| T01 | Spec | Audit whether a proposed reconnect change violates actor invariants. | Spec Guard | Cites invariants, verdict, follow-ups. |
| T02 | Protocol | Review handshake change for Connected-state gating correctness. | Spec Guard | Detects config_complete_id gate requirement. |
| T03 | API | Determine if adding new public function needs updateKotlinAbi and SemVer change. | API Compat Agent | Correct checkKotlinAbi/updateKotlinAbi and SemVer guidance. |
| T04 | API | Review replacement of sealed outcome with kotlin.Result in public API. | API Compat Agent | Flags ADR-005 violation. |
| T05 | Docs | Sync docs after protocol timeout behavior changed. | Docs Sync Agent | Updates protocol/architecture docs mapping. |
| T06 | Docs | Sync docs after contribution workflow command changes. | Docs Sync Agent | Updates CONTRIBUTING and ci-cd references. |
| T07 | Routing | Route mixed task: protocol change plus public API impact. | Workflow Router | Delegates Spec Guard then API Compat. |
| T08 | Routing | Route docs-only drift cleanup task. | Workflow Router | Delegates Docs Sync only. |
| T09 | Policy | Simulate risky command usage and verify warn-only hook messaging. | Hooks | Warning appears, operation not blocked. |
| T10 | Quality | Validate specialist output follows structured report schema fields. | All specialists | Includes schema-complete fields. |
