#!/usr/bin/env python3
"""Permit only MainActivity and its launcher aliases to be exported (P6-04)."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
MAIN_ACTIVITY_NAMES = {
    "com.system.location.service.MainActivity",
    ".MainActivity",
}


def component_name(node: ET.Element) -> str:
    return node.get(f"{ANDROID}name", "")


def is_main_launcher_alias(node: ET.Element) -> bool:
    if node.get(f"{ANDROID}targetActivity") not in MAIN_ACTIVITY_NAMES:
        return False
    filters = node.findall("intent-filter")
    return len(filters) == 1 and sorted((entry.tag, component_name(entry)) for entry in filters[0]) == [
        ("action", "android.intent.action.MAIN"),
        ("category", "android.intent.category.LAUNCHER"),
    ]


def main() -> int:
    root = ET.parse(MANIFEST).getroot()
    application = root.find("application")
    if application is None:
        print("manifest: missing application")
        return 1
    errors = []
    permissions = {
        node.get(f"{ANDROID}name", "") for node in root.findall("uses-permission")
    }
    if "android.permission.QUERY_ALL_PACKAGES" in permissions:
        errors.append("QUERY_ALL_PACKAGES must not be requested; use package visibility queries")
    # Android still filters the developer-options mock-location app list by this manifest
    # declaration even though the permission is deprecated for runtime use (issue #9).
    if "android.permission.ACCESS_MOCK_LOCATION" not in permissions:
        errors.append("ACCESS_MOCK_LOCATION must stay declared for the mock-location app picker")
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for node in application.findall(tag):
            name = component_name(node)
            exported = node.get(f"{ANDROID}exported")
            allowed = (tag == "activity" and name in MAIN_ACTIVITY_NAMES) or (
                tag == "activity-alias" and is_main_launcher_alias(node)
            )
            if exported == "true" and not allowed:
                errors.append(f"unexpected exported {tag}: {name}")
            if tag in ("service", "provider") and exported != "false":
                errors.append(f"{tag} must set exported=false: {name}")
    if errors:
        print("\n".join(errors))
        print(f"manifest exports: {len(errors)} violation(s)")
        return 1
    print("manifest exports: 0 violation(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
