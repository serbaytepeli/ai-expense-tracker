#!/usr/bin/env python3
"""Verify that tests really ran and summarise JUnit XML reports as Markdown.

Usage:
  scripts/ci/junit-summary.py <title> <report-dir> [--require-suite CLASS ...]

Writes a Markdown table to stdout (for $GITHUB_STEP_SUMMARY) and exits 1 when:
  * no test ran at all (missing or empty report directory, zero tests), or
  * a suite named with --require-suite is missing, ran zero tests, or had skipped tests
    (used to prove that the database tests actually ran against PostgreSQL).

Test failures themselves fail the build step; this check catches runs that look green
because tests never executed.
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

KEYS = ("tests", "failures", "errors", "skipped")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("title")
    parser.add_argument("report_dir")
    parser.add_argument("--require-suite", action="append", default=[], metavar="CLASS",
                        help="fully qualified test class that must have run (repeatable)")
    args = parser.parse_args()

    totals = dict.fromkeys(KEYS, 0)
    per_suite: dict[str, dict[str, int]] = {}
    for path in sorted(glob.glob(os.path.join(args.report_dir, "*.xml"))):
        for suite in ET.parse(path).getroot().iter("testsuite"):
            counts = {key: int(suite.get(key, 0)) for key in KEYS}
            name = suite.get("name", os.path.basename(path))
            per_suite[name] = counts
            for key in KEYS:
                totals[key] += counts[key]

    passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    print(f"### {args.title}")
    print()
    print("| Suites | Tests | Passed | Failed | Errors | Skipped |")
    print("|---|---|---|---|---|---|")
    print(f"| {len(per_suite)} | {totals['tests']} | {passed} | {totals['failures']} "
          f"| {totals['errors']} | {totals['skipped']} |")

    problems = []
    if totals["tests"] == 0:
        problems.append(f"No tests ran: no test results in `{args.report_dir}`.")
    for required in args.require_suite:
        counts = per_suite.get(required)
        if counts is None or counts["tests"] == 0:
            problems.append(f"Required test suite `{required}` did not run.")
        elif counts["skipped"] > 0:
            problems.append(f"Required test suite `{required}` skipped {counts['skipped']} test(s).")

    if problems:
        print()
        for problem in problems:
            print(f"**{problem}**")
            print(f"::error::{problem}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
