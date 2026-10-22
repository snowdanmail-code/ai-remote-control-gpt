#!/usr/bin/env python3
"""Review a T042 engineering report offline and prepare a GitHub issue body.

This tool never accesses network, code signing, OAuth tokens or Android gestures.
Reports are already redacted on phone. Nonetheless render only allow-listed
aggregate numbers and vetted engineering recommendations, not arbitrary fields.
"""
import argparse
import json
from pathlib import Path

SCHEMA = "t042-engineering-diagnostics-v1"
WHITELIST = {"T042-DIAG-001", "T042-DIAG-002", "T042-DIAG-003",
             "T042-DIAG-004", "T042-DIAG-005", "T042-DIAG-006",
             "T042-DIAG-007", "T042-DIAG-008"}
INCIDENT_CODES = {"CAPTURE_FRAME_ERROR", "CAPTURE_PERMISSION_ERROR",
                  "OCR_ENGINE_ERROR", "OCR_CONFLICT", "OCR_STALE"}

def read_report(path: Path) -> dict:
    if path.stat().st_size > 500_000:
        raise ValueError("Report too large")
    report = json.loads(path.read_text(encoding="utf-8"))
    if report.get("schema") != SCHEMA:
        raise ValueError("Unsupported report schema")
    assert report.get("assurance", {}).get("spectator_gestures_allowed") is False, "Unsafe report"
    return report

def whitelist_issue(issue: dict) -> bool:
    issue_id = issue.get("issue_id")
    return issue_id in WHITELIST or (
        isinstance(issue_id, str) and issue_id.startswith("T042-INC-") and
        issue_id[9:] in INCIDENT_CODES)

def safe_count(value):
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else 0

def normalized(report: dict) -> dict:
    k = report["quality_kpis"]
    observed = safe_count(k.get("frame_inspections"))
    return {
        "n": observed,
        "uncertain_rate": safe_count(k.get("ambiguous")) / observed if observed else None,
        "two_frame_rate": safe_count(k.get("two_frame_agreements")) / observed if observed else None,
        "manually_reviewed": safe_count(k.get("human_reviewed_recognized")),
        "manually_corrected": safe_count(k.get("human_corrected")),
    }

def compare(current: dict, baseline: dict | None) -> list[str]:
    if baseline is None:
        return ["Baseline comparison: NOT_AVAILABLE (do not infer improvement)."]
    a, b = normalized(current), normalized(baseline)
    if a["n"] < 20 or b["n"] < 20:
        return ["Baseline comparison: INSUFFICIENT_SAMPLES (20 minimum per version)."]
    result = []
    for key in ("uncertain_rate", "two_frame_rate"):
        before, after = b[key], a[key]
        result.append(f"{key}: baseline {before:.1%}, current {after:.1%}; "
                      "sampling conditions must match")
    if a["manually_reviewed"] < 20 or b["manually_reviewed"] < 20:
        result.append("No trustworthy measured card accuracy: fewer than 20 reviewed observations.")
    return result

def render(report: dict, baseline: dict | None = None) -> str:
    data = normalized(report)
    total = data["n"]
    k = report["quality_kpis"]
    perf = report.get("performance", {})
    issue_ids = [
        item["issue_id"]
        for item in report.get("prioritized_issues", [])
        if isinstance(item, dict) and whitelist_issue(item)
    ]
    # No raw logs, screenshots, labels, free-form user text or opponent data.
    lines = [
        "# T042 spectator engineering diagnostic",
        "",
        f"- Application version: {''.join(c for c in str(report.get('provenance', {}).get('app_version', '?'))[:70] if c.isalnum() or c in '._-')}",
        f"- Captured inspection samples: {total}",
        f"- Incomplete candidates: {safe_count(k.get('ambiguous'))}",
        f"- Two-frame agreements (NOT accuracy): {safe_count(k.get('two_frame_agreements'))}",
        f"- Manually reviewed recognized boards: {data['manually_reviewed']}",
        f"- Manually corrected boards: {data['manually_corrected']}",
        f"- Max observed local latency (ms): {safe_count(perf.get('max_ms'))}",
        "",
        "## Issues to investigate (IDs only)",
        ", ".join(issue_ids) if issue_ids else "None automatically identified.",
        "",
        "## Cross-version evaluation",
        *compare(report, baseline),
        "",
        "## Required verification",
        "1. Annotate public cards with independent human review; compare both readers on held-out frames.",
        "2. Prove capture epoch/root, STOP and spectator no-gesture guards are unchanged.",
        "3. Run unit tests, Android instrumentation, APK signature and rollback checks.",
        "4. Do not promote new reader until confirmed correctness improves without regression.",
        "",
        "This summary intentionally omits user-provided evidence text, screenshots, card traces, ",
        "personal identifiers and credentials. Attach the complete private report separately",
        "only after reviewing its contents.",
    ]
    return "\n".join(lines) + "\n"

def main() -> None:
    p = argparse.ArgumentParser(description="Offline T042 engineering diagnostic triage")
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--baseline", type=Path)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    result = render(read_report(args.report),
                    read_report(args.baseline) if args.baseline else None)
    args.output.write_text(result, encoding="utf-8")

if __name__ == "__main__":
    main()
