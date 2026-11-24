"""Coverage-drop gate (T-009): compares this build's JaCoCo totals with those of the latest green build of develop.

    python tools/ci/coverage_gate.py <current jacoco.xml> [<baseline jacoco.xml>]

Fails (exit 1) when the line or branch coverage falls by more than TOLERANCE percentage points. Without a baseline
(the first run, or no green develop build kept its report) it prints the current figures and passes: the fixed
minimum of the JaCoCo check in pom.xml still applies. Writes a Markdown summary to $GITHUB_STEP_SUMMARY when set.

Only the standard library is used, so the CI runner needs nothing installed.
"""

import os
import sys
import xml.etree.ElementTree as ET

# Percentage points a build may lose before it is refused: room for rounding, not for untested code.
TOLERANCE = float(os.environ.get("COVERAGE_TOLERANCE", "0.1"))
COUNTERS = ("LINE", "BRANCH")


def totals(path):
    """The report's bundle-level counters, as percentages."""
    root = ET.parse(path).getroot()
    found = {}
    # The bundle's own counters are the direct children of <report>; package and class counters sit deeper.
    for counter in root.findall("counter"):
        kind = counter.get("type")
        if kind in COUNTERS:
            missed = int(counter.get("missed"))
            covered = int(counter.get("covered"))
            found[kind] = 100.0 * covered / (missed + covered) if missed + covered else 100.0
    return found


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    current = totals(argv[1])
    baseline = totals(argv[2]) if len(argv) > 2 and os.path.exists(argv[2]) else None

    rows = []
    failed = []
    for kind in COUNTERS:
        now = current.get(kind)
        before = baseline.get(kind) if baseline else None
        if now is None:
            continue
        if before is None:
            rows.append(f"| {kind.lower()} | {now:.2f} % | - | - |")
            continue
        delta = now - before
        rows.append(f"| {kind.lower()} | {now:.2f} % | {before:.2f} % | {delta:+.2f} |")
        if delta < -TOLERANCE:
            failed.append(f"{kind.lower()} coverage fell from {before:.2f} % to {now:.2f} % ({delta:+.2f} points)")

    summary = ["### Coverage against develop", "", "| Counter | This build | develop | Change |", "|---|---|---|---|"]
    summary += rows
    if baseline is None:
        summary += ["", "No report of a green develop build was found: nothing to compare, the fixed minimum applies."]
    elif failed:
        summary += ["", f"**Refused:** more than {TOLERANCE} points lost. Add tests for the new code."]
    else:
        summary += ["", f"Kept within {TOLERANCE} points."]
    text = "\n".join(summary)
    print(text)
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as out:
            out.write(text + "\n")

    for message in failed:
        print(f"::error title=Coverage dropped::{message}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
