#!/usr/bin/env python3
"""Summarise JUnit XML reports as Markdown (for $GITHUB_STEP_SUMMARY).

Usage: scripts/ci/junit-summary.py <title> <report-dir>

Prints the totals and fails (exit 1) when no test ran, so a misconfigured run
cannot look green with zero tests. Test failures themselves are reported by the
test step; this script only makes the counts visible.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    title, report_dir = sys.argv[1], sys.argv[2]

    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    suites = 0
    for path in sorted(glob.glob(os.path.join(report_dir, "*.xml"))):
        root = ET.parse(path).getroot()
        for suite in root.iter("testsuite"):
            suites += 1
            for key in totals:
                totals[key] += int(suite.get(key, 0))

    passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    print(f"### {title}")
    print()
    print("| Suites | Tests | Passed | Failed | Errors | Skipped |")
    print("|---|---|---|---|---|---|")
    print(f"| {suites} | {totals['tests']} | {passed} | {totals['failures']} "
          f"| {totals['errors']} | {totals['skipped']} |")

    if totals["tests"] == 0:
        print()
        print(f"**No tests found in `{report_dir}`.**")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
