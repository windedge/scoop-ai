#!/usr/bin/env python3
"""Summarize JUnit XML test results into one line plus failure list.

Usage:
    python scripts/test-report.py [DIR ...]

Default dirs: core/build/test-results/jvmTest, tools/build/test-results/jvmTest,
cli/build/test-results/test. Prints ``tests=N failures=N errors=N skipped=N``
and, for each failed/errored case, its source file, full test name and the
first line of the failure message. Exit code 0 when green, 1 otherwise.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DEFAULT_DIRS = (
    "core/build/test-results/jvmTest",
    "tools/build/test-results/jvmTest",
    "cli/build/test-results/test",
)


def collect_xml_files(dirs):
    for d in dirs:
        base = Path(d)
        if not base.is_dir():
            print(f"warn: directory not found: {d}", file=sys.stderr)
            continue
        yield from sorted(base.glob("TEST-*.xml"))


def first_line(text):
    if not text:
        return ""
    return text.strip().splitlines()[0]


def main():
    dirs = sys.argv[1:] or DEFAULT_DIRS
    total = failures = errors = skipped = 0
    failed_cases = []

    for path in collect_xml_files(dirs):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"warn: cannot parse {path}: {exc}", file=sys.stderr)
            continue

        total += int(root.get("tests", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))

        for case in root.iter("testcase"):
            problem = case.find("failure") if case.find("failure") is not None else case.find("error")
            if problem is None:
                continue
            classname = case.get("classname", "")
            name = case.get("name", "")
            failed_cases.append((path, f"{classname}.{name}", first_line(problem.get("message") or (problem.text or ""))))

    print(f"tests={total} failures={failures} errors={errors} skipped={skipped}")
    for path, test_name, message in failed_cases:
        print(f"FAIL {path}: {test_name}: {message}")
    return 1 if (failures or errors) else 0


if __name__ == "__main__":
    sys.exit(main())
