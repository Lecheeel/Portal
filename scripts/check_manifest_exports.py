#!/usr/bin/env python3
"""Fail if app components other than MainActivity are exported (P6-04)."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
ALLOWED_EXPORTED = {"com.system.location.service.MainActivity", ".MainActivity"}


def component_name(node: ET.Element) -> str:
    return node.get(f"{ANDROID}name", "")


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
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for node in application.findall(tag):
            name = component_name(node)
            exported = node.get(f"{ANDROID}exported")
            if exported == "true" and name not in ALLOWED_EXPORTED:
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
