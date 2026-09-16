#!/usr/bin/env python3
"""Which findings from BUGS.md are pinned by tests, and which live only in the document.

A finding is tied to its tests by @Tag("F-xx"). Both tests of a pair carry it: the passing
one that pins today's behaviour and the @Disabled one that states the expected behaviour.

    tools/findings.py              a table, and a non-zero exit if the two sides disagree
    tools/findings.py --markdown   the same table as markdown, for TRACEABILITY.md

Exits non-zero when a tag names a finding BUGS.md does not list, or when a test's display
name mentions (F-xx) without the matching tag: both mean the link has silently rotted.
"""

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUGS = ROOT / "BUGS.md"
TESTS = ROOT / "service/src/test/java/lab/qa/tests"

FINDING = re.compile(r"F-\d{2}")
METHOD = re.compile(r"^\s*(?:@\w+\s*)?(?:public |private |static )*void (\w+)\s*\(")


def findings_from_bugs():
    """The summary table at the top of BUGS.md: | F-01 | title | severity | evidence | status |."""
    found = {}
    for line in BUGS.read_text().splitlines():
        cells = [c.strip() for c in line.split("|")[1:-1]]
        if len(cells) >= 5 and FINDING.fullmatch(cells[0]):
            found[cells[0]] = {"title": cells[1], "severity": cells[2], "status": cells[4]}
    return found


def annotations_from_tests():
    """Every @Tag / @Disabled / @DisplayName that names a finding, tied to the method below it."""
    tagged, disabled, named = {}, set(), {}
    for path in sorted(TESTS.rglob("*.java")):
        lines = path.read_text().splitlines()
        for i, line in enumerate(lines):
            stripped = line.strip()
            if not stripped.startswith(("@Tag(", "@Disabled(", "@DisplayName(")):
                continue
            ids = FINDING.findall(stripped)
            if not ids:
                continue
            method = next((METHOD.match(nxt).group(1) for nxt in lines[i + 1:] if METHOD.match(nxt)), None)
            if method is None:
                continue
            where = (path.relative_to(TESTS).as_posix(), method)
            if stripped.startswith("@Tag("):
                tagged.setdefault(ids[0], []).append(where)
            elif stripped.startswith("@Disabled("):
                disabled.add(where)
            else:
                named.setdefault(where, set()).update(ids)
    return tagged, disabled, named


def collect():
    bugs = findings_from_bugs()
    tagged, disabled, named = annotations_from_tests()

    rows = []
    for fid in sorted(bugs):
        tests = sorted(tagged.get(fid, []))
        pins = [t for t in tests if t not in disabled]
        expects = [t for t in tests if t in disabled]
        if pins:
            state = "pinned"
        elif expects:
            state = "expected only"
        else:
            state = "document only"
        rows.append({"id": fid, **bugs[fid], "pins": pins, "expects": expects, "state": state})

    problems = []
    for fid, tests in sorted(tagged.items()):
        if fid not in bugs:
            problems.append(f'@Tag("{fid}") on {len(tests)} test(s), but BUGS.md has no {fid}')
    for (path, method), ids in sorted(named.items()):
        missing = {i for i in ids if (path, method) not in tagged.get(i, [])}
        for fid in sorted(missing):
            problems.append(f"{path}#{method} is named ({fid}) but carries no @Tag(\"{fid}\")")
    return rows, problems


def short(tests):
    return ", ".join(f"{p.rsplit('/', 1)[-1][:-5]}#{m}" for p, m in tests) or "—"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--markdown", action="store_true", help="print the table as markdown")
    args = parser.parse_args()

    rows, problems = collect()

    if args.markdown:
        counted = {s: sum(1 for r in rows if r["state"] == s) for s in
                   ("pinned", "expected only", "document only")}
        print("## Findings: pinned by tests, or only in the document\n")
        print(", ".join(f"**{n}** {s}" for s, n in counted.items()) + "\n")
        print("| Finding | Pinned by | Expected behaviour | State |")
        print("|---|---|---|---|")
        for r in rows:
            print(f"| {r['id']} {r['title']} | {short(r['pins'])} | {short(r['expects'])} | {r['state']} |")
    else:
        width = max(len(r["state"]) for r in rows)
        for r in rows:
            print(f"{r['id']}  {r['state']:<{width}}  {len(r['pins'])} pinning, "
                  f"{len(r['expects'])} disabled   {r['title'][:54]}")
            if r["pins"] or r["expects"]:
                print(f"      {short(r['pins'] + r['expects'])}")
        counted = {s: sum(1 for r in rows if r["state"] == s) for s in
                   ("pinned", "expected only", "document only")}
        print(f"\n{len(rows)} findings: " + ", ".join(f"{n} {s}" for s, n in counted.items()))

    if problems:
        print("\nThe document and the tests disagree:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
