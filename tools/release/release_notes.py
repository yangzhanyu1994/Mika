#!/usr/bin/env python3
"""Print the Chinese notes for one version from the bundled update history.

The About screen reads app/src/main/assets/fork-release-history.md. The signed
release must publish that same text, not an English commit list.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/assets/fork-release-history.md"
HEADING = re.compile(r"(?m)^#### \[v(\d+\.\d+\.\d+)]\([^\n]*\) · (\d{4}-\d{2}-\d{2})\s*$")


def notes_for(version: str, text: str) -> str:
    section = text.split("### 近期版本更新说明", 1)[-1]
    hits = list(HEADING.finditer(section))
    for index, match in enumerate(hits):
        if match.group(1) != version:
            continue
        end = hits[index + 1].start() if index + 1 < len(hits) else len(section)
        notes = section[match.end():end].strip()
        if not notes:
            raise SystemExit(f"v{version} has an empty Chinese update note")
        return notes
    raise SystemExit(f"v{version} is missing from {SOURCE}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: release_notes.py VERSION")
    version = sys.argv[1][1:] if sys.argv[1].startswith("v") else sys.argv[1]
    print(notes_for(version, SOURCE.read_text(encoding="utf-8")))


if __name__ == "__main__":
    main()
