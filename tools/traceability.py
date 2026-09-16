#!/usr/bin/env python3
"""Check TRACEABILITY.md against the tests it claims, and summarise what it says.

The matrix is written by hand, because its Status column and its reading of the gaps are
judgement, not data. What can be checked mechanically is the link: every `Class#method` it
names must exist, and every test must belong to some requirement.

    tools/traceability.py              the summary, and a non-zero exit if a link is broken
    tools/traceability.py --markdown   the same as markdown, for a CI step summary

Errors (non-zero exit):
  - the matrix names a test that no longer exists, so a rename broke the link;
  - a row's Status is not one of the four the document defines;
  - the Coverage summary table disagrees with the rows it summarises.

Warnings (reported, do not fail): tests that belong to no requirement. A new test with no
requirement is usually a requirement nobody wrote down.
"""

import argparse
import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MATRIX = ROOT / "TRACEABILITY.md"
TESTS = ROOT / "service/src/test/java/lab/qa/tests"

REQUIREMENT = re.compile(r"(BR|API|INT|EVT|DATA|OPS)-\d{2}")
REFERENCE = re.compile(r"`(\w+Test)?#(\w+)`")
TEST_METHOD = re.compile(r"^\s*(?:public |private |static )*void (\w+)\s*\(")
IS_TEST = re.compile(r"^\s*@(Test|ParameterizedTest)\b")
STATES = ("Uncovered", "Known bug", "Partial", "Covered")


def classify(status):
    plain = status.replace("*", "").strip().lower()
    for state in STATES:
        if plain.startswith(state.lower()):
            return state
    return None


def requirements():
    """Rows of the six requirement tables: id, group, referenced tests, state."""
    rows = []
    for line in MATRIX.read_text().splitlines():
        cells = [c.strip() for c in line.split("|")[1:-1]]
        if len(cells) != 5 or not REQUIREMENT.fullmatch(cells[0]):
            continue
        refs, last_class = [], None
        for klass, method in REFERENCE.findall(cells[3]):
            last_class = klass or last_class
            if last_class:
                refs.append((last_class, method))
        rows.append({"id": cells[0], "group": cells[0].split("-")[0],
                     "tests": refs, "status": cells[4], "state": classify(cells[4])})
    return rows


def summary_table():
    """The Coverage summary table, so the counts it prints can be checked against the rows."""
    counts = {}
    for line in MATRIX.read_text().splitlines():
        cells = [c.strip() for c in line.split("|")[1:-1]]
        if len(cells) == 6 and "—" in cells[0] and cells[1].isdigit():
            group = cells[0].split("—")[0].strip()
            counts[group] = [int(c) for c in cells[1:]]
    return counts


def actual_tests():
    """Every @Test / @ParameterizedTest method in the suite, by simple class name."""
    found = collections.defaultdict(set)
    for path in sorted(TESTS.rglob("*Test.java")):
        lines = path.read_text().splitlines()
        marked = False
        for line in lines:
            if IS_TEST.match(line):
                marked = True
                continue
            match = TEST_METHOD.match(line)
            if match:
                if marked:
                    found[path.stem].add(match.group(1))
                marked = False
    return found


def check():
    rows, suite, declared = requirements(), actual_tests(), summary_table()
    errors, warnings = [], []

    referenced = set()
    for row in rows:
        if row["state"] is None:
            errors.append(f"{row['id']}: status is not one of {', '.join(STATES)} — {row['status'][:60]}")
        for klass, method in row["tests"]:
            if method in suite.get(klass, ()):
                referenced.add((klass, method))
            else:
                errors.append(f"{row['id']} names {klass}#{method}, which no longer exists")

    for klass, methods in sorted(suite.items()):
        for method in sorted(methods):
            if (klass, method) not in referenced:
                warnings.append(f"{klass}#{method}")

    counted = collections.defaultdict(lambda: collections.Counter())
    for row in rows:
        counted[row["group"]][row["state"]] += 1
    for group, numbers in sorted(declared.items()):
        actual = counted.get(group)
        if actual is None:
            continue
        expected = [sum(actual.values())] + [actual[s] for s in ("Covered", "Partial", "Known bug", "Uncovered")]
        if numbers[:5] != expected[:5]:
            errors.append(f"Coverage summary says {group} is {numbers[:5]}, the rows say {expected[:5]}")

    return rows, counted, warnings, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()

    rows, counted, warnings, errors = check()
    groups = sorted(counted, key=lambda g: -sum(counted[g].values()))
    total = collections.Counter()
    for group in groups:
        total.update(counted[group])

    header = ["Group", "Requirements", "Covered", "Partial", "Known bug", "Uncovered"]
    body = [[g, sum(counted[g].values()), *(counted[g][s] for s in ("Covered", "Partial", "Known bug", "Uncovered"))]
            for g in groups]
    body.append(["**Total**", sum(total.values()), *(total[s] for s in ("Covered", "Partial", "Known bug", "Uncovered"))])

    if args.markdown:
        print("## Traceability: requirements to tests\n")
        print("| " + " | ".join(header) + " |")
        print("|" + "---|" * len(header))
        for line in body:
            print("| " + " | ".join(str(c) for c in line) + " |")
        print(f"\n{len(rows)} requirements, {sum(len(r['tests']) for r in rows)} test references, all resolved.")
        if warnings:
            print(f"\n<details><summary>{len(warnings)} tests belong to no requirement</summary>\n")
            for w in warnings:
                print(f"- `{w}`")
            print("\n</details>")
    else:
        widths = [max(len(str(line[i])) for line in [header] + body) for i in range(len(header))]
        for line in [header] + body:
            print("  " + "  ".join(str(c).ljust(w) for c, w in zip(line, widths)))
        print(f"\n{len(rows)} requirements, {sum(len(r['tests']) for r in rows)} test references, all resolved.")
        if warnings:
            print(f"\n{len(warnings)} tests belong to no requirement:")
            for w in warnings:
                print(f"  {w}")

    if errors:
        print("\nThe matrix and the tests disagree:", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
