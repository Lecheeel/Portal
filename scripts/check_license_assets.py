#!/usr/bin/env python3
"""Ensure shipped APK asset licenses match the repository LICENSE/NOTICE (P0-05)."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGETS = (
    ROOT / "app" / "src" / "main" / "assets",
    ROOT / "detector" / "src" / "main" / "assets",
)


def main() -> int:
    errors = []
    for name in ("LICENSE", "NOTICE"):
        source = ROOT / name
        if not source.is_file():
            errors.append(f"missing repository {name}")
            continue
        expected = source.read_bytes()
        for directory in TARGETS:
            target = directory / name
            if not target.is_file():
                errors.append(f"missing {target.relative_to(ROOT).as_posix()}")
            elif target.read_bytes() != expected:
                errors.append(f"{target.relative_to(ROOT).as_posix()} differs from {name}")
    if errors:
        print("\n".join(errors))
        print(f"license assets: {len(errors)} violation(s)")
        return 1
    print("license assets: 0 violation(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
