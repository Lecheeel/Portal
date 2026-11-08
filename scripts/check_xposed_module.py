#!/usr/bin/env python3
"""Verify Portal's native libxposed 102 sources and APK entry, without Android tools."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ENTRY = "com.system.location.service.hook.PortalModule"
RESOURCE_DIR = "xposed/src/main/resources/META-INF/xposed"
PROPERTIES = {
    "minApiVersion": "102",
    "targetApiVersion": "102",
    "staticScope": "true",
    "autoHotReload": "true",
}
# Modern modules use "system" for system_server; "android" is the legacy spelling.
SCOPES = {
    "system", "com.android.phone", "com.android.location.fused",
    "com.xiaomi.location.fused", "com.oplus.location", "com.android.providers.settings",
}
LEGACY_METADATA = {
    "xposedmodule", "xposeddescription", "xposedminversion", "xposedscope", "xposedsharedprefs",
}
ANDROID = "{http://schemas.android.com/apk/res/android}"


def lines(data: bytes) -> list[str]:
    return [line.strip() for line in data.decode("utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith(("#", "!"))]


def validate_metadata(resources: dict[str, bytes]) -> None:
    if lines(resources["java_init.list"]) != [ENTRY]:
        raise ValueError("java_init.list must contain only PortalModule")
    properties = {}
    for line in lines(resources["module.prop"]):
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key in properties:
            raise ValueError("module.prop contains malformed or duplicate properties")
        properties[key] = value
    if any(properties.get(key) != value for key, value in PROPERTIES.items()):
        raise ValueError("module.prop must target API 102 with static scope and system_server hot reload enabled")
    scope = lines(resources["scope.list"])
    if set(scope) != SCOPES or len(scope) != len(SCOPES):
        raise ValueError("scope.list must declare Portal's six modern scopes, including system")


def source_violations(root: Path = ROOT) -> list[str]:
    errors = []
    resources = {}
    for name in ("java_init.list", "module.prop", "scope.list"):
        path = root / RESOURCE_DIR / name
        if not path.is_file():
            errors.append(f"missing {path.relative_to(root).as_posix()}")
        else:
            resources[name] = path.read_bytes()
    if len(resources) == 3:
        try:
            validate_metadata(resources)
        except (ValueError, UnicodeError) as error:
            errors.append(str(error))
    banned = re.compile(r"de\.robv\.android\.xposed|hook\.compat\b|\b(?:XC_MethodHook|XposedBridge|XposedHelpers)\b")
    for module in ("app", "xposed"):
        source = root / module / "src" / "main"
        for path in source.rglob("*"):
            if path.name == "xposed_init":
                errors.append(f"legacy entry remains: {path.relative_to(root).as_posix()}")
            if path.suffix in {".kt", ".java"} and banned.search(path.read_text(encoding="utf-8")):
                errors.append(f"legacy/compat hook reference: {path.relative_to(root).as_posix()}")
            if path.name == "AndroidManifest.xml":
                try:
                    tree = ET.parse(path)
                    for node in tree.iter("meta-data"):
                        if node.get(f"{ANDROID}name") in LEGACY_METADATA:
                            errors.append(f"legacy module metadata: {path.relative_to(root).as_posix()}")
                except ET.ParseError as error:
                    errors.append(f"invalid manifest: {path.relative_to(root).as_posix()}: {error}")
    build_paths = [root / "settings.gradle.kts", root / "gradle/libs.versions.toml"]
    build_paths.extend(root.glob("*/build.gradle.kts"))
    for path in build_paths:
        if not path.is_file():
            continue
        content = path.read_text(encoding="utf-8")
        if re.search(r"de\.robv\.android\.xposed|api\.xposed\.info|\b(?:implementation|api)\s*\(\s*(?:libs\.libxposed\.api\b|[\"']io\.github\.libxposed:api:)", content):
            errors.append(f"legacy or packaged API dependency: {path.relative_to(root).as_posix()}")
    return errors


def dex_class_descriptors(data: bytes) -> set[str]:
    """Read class definitions, avoiding false positives from API type references in DEX."""
    if len(data) < 112 or not re.fullmatch(rb"dex\n[0-9]{3}\x00", data[:8]):
        raise ValueError("invalid DEX header")

    def u32(offset: int) -> int:
        if not 0 <= offset <= len(data) - 4:
            raise ValueError("DEX index is out of bounds")
        return struct.unpack_from("<I", data, offset)[0]

    string_count, string_offset = u32(56), u32(60)
    type_count, type_offset = u32(64), u32(68)
    class_count, class_offset = u32(96), u32(100)
    for count, offset, size in ((string_count, string_offset, 4), (type_count, type_offset, 4),
                                (class_count, class_offset, 32)):
        if offset > len(data) or count > (len(data) - offset) // size:
            raise ValueError("truncated DEX index table")
    result = set()
    for index in range(class_count):
        type_index = u32(class_offset + index * 32)
        if type_index >= type_count:
            raise ValueError("DEX class type index is out of bounds")
        string_index = u32(type_offset + type_index * 4)
        if string_index >= string_count:
            raise ValueError("DEX descriptor index is out of bounds")
        offset = u32(string_offset + string_index * 4)
        # Skip the string's ULEB128 UTF-16 length, then read its ASCII class descriptor.
        for _ in range(5):
            if offset >= len(data):
                raise ValueError("truncated DEX string")
            byte = data[offset]
            offset += 1
            if byte < 128:
                break
        else:
            raise ValueError("invalid DEX string length")
        end = data.find(b"\0", offset)
        if end == -1:
            raise ValueError("unterminated DEX descriptor")
        result.add(data[offset:end].decode("utf-8"))
    return result


def validate_apk(apk: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        resources = {}
        for name in ("java_init.list", "module.prop", "scope.list"):
            full_name = f"META-INF/xposed/{name}"
            if names.count(full_name) != 1:
                raise ValueError(f"{apk.name}: missing or duplicate {full_name}")
            resources[name] = archive.read(full_name)
        validate_metadata(resources)
        if "assets/xposed_init" in names:
            raise ValueError(f"{apk.name}: legacy assets/xposed_init remains")
        native_libs = [name for name in names if name.endswith("/liblocationext.so")]
        if native_libs:
            raise ValueError(f"{apk.name}: plain native payload remains in {native_libs[0]}")
        if "assets/liblocationext.bin" in names:
            asset = archive.read("assets/liblocationext.bin")
            if asset.startswith(b"\x7fELF"):
                raise ValueError(f"{apk.name}: native asset is not encrypted")
        dex_names = [name for name in names if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)]
        if names.count("AndroidManifest.xml") != 1 or len(set(dex_names)) != len(dex_names):
            raise ValueError(f"{apk.name}: missing or duplicate manifest/DEX entries")
        manifest = archive.read("AndroidManifest.xml")
        if manifest.lstrip().startswith(b"<"):
            tree = ET.fromstring(manifest)
            if any(node.get(f"{ANDROID}name") in LEGACY_METADATA for node in tree.iter("meta-data")):
                raise ValueError(f"{apk.name}: legacy module metadata remains in manifest")
        # AXML string pools can encode names in UTF-8 or UTF-16LE.
        if any(key.encode("utf-8") + b"\0" in manifest or
               key.encode("utf-16le") + b"\0\0" in manifest for key in LEGACY_METADATA):
            raise ValueError(f"{apk.name}: legacy module metadata remains in manifest")
        descriptors = set()
        for name in names:
            if name in dex_names:
                descriptors.update(dex_class_descriptors(archive.read(name)))
            if name.startswith(("io/github/libxposed/api/", "de/robv/android/xposed/")):
                raise ValueError(f"{apk.name}: framework API classes must not be packaged")
        if f"L{ENTRY.replace('.', '/')};" not in descriptors:
            raise ValueError(f"{apk.name}: PortalModule class definition is missing")
        forbidden = ("Lio/github/libxposed/api/", "Lde/robv/android/xposed/",
                     "Lcom/system/location/service/hook/compat/")
        if any(descriptor.startswith(forbidden) for descriptor in descriptors):
            raise ValueError(f"{apk.name}: framework API or legacy compat classes are packaged")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, help="also verify every APK in this directory")
    args = parser.parse_args()
    errors = source_violations()
    if args.apk_dir is not None:
        apks = sorted(args.apk_dir.glob("*.apk"))
        if not apks:
            errors.append(f"no APKs found in {args.apk_dir}")
        for apk in apks:
            try:
                validate_apk(apk)
                print(f"Verified modern Xposed entry: {apk.name}")
            except (ValueError, KeyError, UnicodeError, ET.ParseError, zipfile.BadZipFile) as error:
                errors.append(str(error))
    for error in errors:
        print(f"FAIL {error}")
    print(f"modern Xposed module: {len(errors)} violation(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
