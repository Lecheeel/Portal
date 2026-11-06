#!/usr/bin/env python3
"""Fail if sensitive files can be included in Android backup/device transfer (P6-04)."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res" / "xml"

REQUIRED = (
    ("file", "environment/"),
    ("sharedpref", "fused_ext.xml"),
    ("file", "library/"),
    ("sharedpref", "sdk_privacy.xml"),
    ("sharedpref", "scenario_runtime.xml"),
)


def exclusions(node: ET.Element) -> set[tuple[str, str]]:
    return {(child.get("domain", ""), child.get("path", "")) for child in node.findall("exclude")}


def check_section(name: str, actual: set[tuple[str, str]]) -> list[str]:
    return [f"{name}: missing domain={domain} path={path}" for domain, path in REQUIRED if (domain, path) not in actual]


def main() -> int:
    errors = []
    backup = ET.parse(RES / "backup_rules.xml").getroot()
    errors += check_section("backup_rules", exclusions(backup))

    extraction = ET.parse(RES / "data_extraction_rules.xml").getroot()
    for section in ("cloud-backup", "device-transfer"):
        node = extraction.find(section)
        if node is None:
            errors.append(f"data_extraction_rules: missing {section}")
            continue
        errors += check_section(f"data_extraction_rules/{section}", exclusions(node))

    if errors:
        print("\n".join(errors))
        print(f"backup rules: {len(errors)} violation(s)")
        return 1
    print("backup rules: 0 violation(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
