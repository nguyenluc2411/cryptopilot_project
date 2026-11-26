"""Checks a JMeter result file against the HTTP targets of SRS 4.2.3 (T-094, D-164).

    python tools/perf/check_targets.py results.jtl [summary.md]

Each request label is held to the target of its prefix at the 95th percentile: "read" 500 ms (read API, 200 concurrent
users), "calc" 300 ms (risk calculation on SCR-17), "dashboard" 2,000 ms (Performance Dashboard). Labels starting
"setup" are not measured. The error rate of the measured requests must stay under 1 %. Prints a Markdown table (and
writes it to summary.md when given); exit 1 when a target is missed. Standard library only.
"""

import csv
import math
import sys
from collections import defaultdict

TARGETS_MS = {"read": 500, "calc": 300, "dashboard": 2000}
MAX_ERROR_RATE = 0.01


def percentile(values, share):
    """The nearest-rank percentile, as JMeter's dashboard reports it."""
    ordered = sorted(values)
    rank = max(1, math.ceil(share * len(ordered)))
    return ordered[rank - 1]


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    elapsed = defaultdict(list)
    errors = defaultdict(int)
    with open(argv[1], newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source):
            label = row["label"]
            if label.startswith("setup"):
                continue
            elapsed[label].append(int(row["elapsed"]))
            if row["success"].lower() != "true":
                errors[label] += 1

    lines = [
        "| Request | Samples | p95 (ms) | Target (ms) | Errors | Result |",
        "|---|---|---|---|---|---|",
    ]
    missed = []
    total = sum(len(values) for values in elapsed.values())
    failed = sum(errors.values())
    for label in sorted(elapsed):
        prefix = label.split(" ", 1)[0]
        target = TARGETS_MS.get(prefix)
        p95 = percentile(elapsed[label], 0.95)
        ok = target is None or p95 <= target
        if not ok:
            missed.append(f"{label}: p95 {p95} ms over {target} ms")
        lines.append(
            f"| {label} | {len(elapsed[label])} | {p95} | {target if target else '-'} | {errors[label]} | "
            f"{'met' if ok else 'MISSED'} |"
        )
    rate = failed / total if total else 1.0
    if rate >= MAX_ERROR_RATE:
        missed.append(f"error rate {rate:.2%} not under {MAX_ERROR_RATE:.0%}")
    lines += ["", f"Measured requests: {total}, errors: {failed} ({rate:.2%})."]
    lines += ["", "**All targets met.**" if not missed else "**Missed:** " + "; ".join(missed)]
    text = "\n".join(lines)
    print(text)
    if len(argv) > 2:
        with open(argv[2], "w", encoding="utf-8") as out:
            out.write("# SRS 4.2.3 targets\n\n" + text + "\n")
    return 1 if missed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
