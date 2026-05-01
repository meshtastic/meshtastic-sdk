/*
 * Meshtastic — open source mesh radio
 * Copyright © 2026 Meshtastic LLC
 *
 * Licensed under the GPL-3.0-or-later license (see LICENSE or https://www.gnu.org/licenses/gpl-3.0.html)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.meshtastic.cli.conformance

/**
 * Render a list of [ScenarioResult]s as the markdown block expected by
 * `MANUAL-TEST-RESULTS.md`. The format mirrors the template at
 * `docs/manual-tests.md` § "Recording results" so reviewers can paste the output
 * verbatim into a release-candidate doc.
 */
internal object Transcript {

    fun render(
        results: List<ScenarioResult>,
        candidate: String,
        tester: String,
        device: String,
        host: String,
    ): String = buildString {
        appendLine("# Conformance — $candidate")
        appendLine()
        appendLine("- Tester: $tester")
        appendLine("- Date: ${java.time.LocalDate.now()}")
        appendLine("- Device(s): $device")
        appendLine("- Host: $host")
        appendLine()
        appendLine("| ID | Scenario | Status | Duration | Notes |")
        appendLine("|----|----------|--------|----------|-------|")
        for (r in results) {
            val status = when (r.status) {
                ScenarioResult.Status.PASS -> "✓ PASS"
                ScenarioResult.Status.FAIL -> "✗ FAIL"
                ScenarioResult.Status.SKIP -> "— SKIP"
            }
            val duration = if (r.durationMs > 0) "${r.durationMs} ms" else "—"
            val notes = r.message.replace("|", "\\|").replace('\n', ' ')
            appendLine("| ${r.id} | ${r.name} | $status | $duration | $notes |")
        }
        appendLine()
        val passed = results.count { it.status == ScenarioResult.Status.PASS }
        val failed = results.count { it.status == ScenarioResult.Status.FAIL }
        val skipped = results.count { it.status == ScenarioResult.Status.SKIP }
        appendLine("**Summary:** $passed passed, $failed failed, $skipped skipped (of ${results.size} scenarios).")
    }

    /** Concise human-readable line for stdout (one per scenario) — used outside `--json` mode. */
    fun line(r: ScenarioResult): String {
        val symbol = when (r.status) {
            ScenarioResult.Status.PASS -> "✓"
            ScenarioResult.Status.FAIL -> "✗"
            ScenarioResult.Status.SKIP -> "—"
        }
        val duration = if (r.durationMs > 0) " (${r.durationMs} ms)" else ""
        return "$symbol  ${r.id}  ${r.name}$duration  —  ${r.message}"
    }
}
