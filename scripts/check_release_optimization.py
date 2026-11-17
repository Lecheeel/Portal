#!/usr/bin/env python3
"""Check compressed Release DEX and framework/JNI callbacks after R8."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import struct
import zipfile

HOOKER = "Lio/github/libxposed/api/XposedInterface$Hooker;"
CHAIN = "Lio/github/libxposed/api/XposedInterface$Chain;"
PREFIX = "Lcom/system/location/service/"
ENTRY = PREFIX + "hook/PortalModule;"
JNI = {
    PREFIX + "jni/Dobby;": {
        ("setStatus", "(Z)V"),
        ("setSensorFrame", "(JIJ[F[F[FF)V"),
        ("prepareSensors", "()Z"),
    },
    PREFIX + "jni/AntiDetect;": {("nativeSetEnabled", "(Z)Z")},
}


def dex_definitions(data: bytes) -> dict:
    """Read class_data definitions, not just method_ids (which include references)."""
    if len(data) < 112 or not re.fullmatch(rb"dex\n[0-9]{3}\0", data[:8]):
        raise ValueError("invalid DEX header")

    def u32(offset):
        if offset < 0 or offset + 4 > len(data):
            raise ValueError("truncated DEX")
        return struct.unpack_from("<I", data, offset)[0]

    def table(header, width):
        count, offset = u32(header), u32(header + 4)
        if offset > len(data) or count > (len(data) - offset) // width:
            raise ValueError("truncated DEX table")
        return [offset + i * width for i in range(count)]

    def uleb(offset):
        result = 0
        for shift in range(0, 35, 7):
            if offset >= len(data):
                raise ValueError("truncated DEX ULEB128")
            value = data[offset]
            offset += 1
            result |= (value & 127) << shift
            if value < 128:
                return result, offset
        raise ValueError("invalid DEX ULEB128")

    strings = []
    for pos in table(56, 4):
        _, start = uleb(u32(pos))
        end = data.find(b"\0", start)
        if end < 0:
            raise ValueError("unterminated DEX string")
        # Only descriptors and method names are used below; tolerate MUTF-8 strings.
        strings.append(data[start:end].decode("utf-8", errors="replace"))
    types = [strings[u32(pos)] for pos in table(64, 4)]

    def type_list(offset):
        if not offset:
            return []
        count = u32(offset)
        if count > (len(data) - offset - 4) // 2:
            raise ValueError("truncated DEX type list")
        return [types[struct.unpack_from("<H", data, offset + 4 + i * 2)[0]]
                for i in range(count)]

    protos = ["(" + "".join(type_list(u32(pos + 8))) + ")" + types[u32(pos + 4)]
              for pos in table(72, 12)]
    methods = []
    for pos in table(88, 8):
        owner, proto, name = struct.unpack_from("<HHI", data, pos)
        methods.append((types[owner], strings[name], protos[proto]))
    classes = {}
    for pos in table(96, 32):
        owner = types[u32(pos)]
        definitions = {}
        offset = u32(pos + 24)
        if offset:
            counts = []
            for _ in range(4):
                count, offset = uleb(offset)
                counts.append(count)
            for _ in range(counts[0] + counts[1]):
                _, offset = uleb(offset)
                _, offset = uleb(offset)
            for count in counts[2:]:
                method_index = 0
                for _ in range(count):
                    diff, offset = uleb(offset)
                    flags, offset = uleb(offset)
                    code, offset = uleb(offset)
                    method_index += diff
                    method_owner, name, proto = methods[method_index]
                    if method_owner != owner:
                        raise ValueError("DEX method owner mismatch")
                    definitions[name, proto] = (flags, code)
        classes[owner] = (type_list(u32(pos + 12)), definitions)
    return classes


def validate_callbacks(classes: dict) -> None:
    entry_methods = classes.get(ENTRY, ([], {}))[1]
    for name in ("onModuleLoaded", "onSystemServerStarting", "onPackageReady"):
        if not any(method == name and code for (method, _), (_, code) in entry_methods.items()):
            raise ValueError(f"PortalModule callback missing: {name}")
    hookers = [(owner, methods) for owner, (interfaces, methods) in classes.items()
               if HOOKER in interfaces]
    if not hookers:
        raise ValueError("no Xposed Hooker implementations")
    for owner, methods in hookers:
        flags, code = methods.get(("intercept", f"({CHAIN})Ljava/lang/Object;"), (0, 0))
        if not flags & 1 or not code:
            raise ValueError(f"Xposed intercept implementation missing: {owner}")
        if not owner.startswith(PREFIX):
            raise ValueError(f"Hooker moved outside the stack-cleaning prefix: {owner}")
    for owner, required in JNI.items():
        methods = classes.get(owner, ([], {}))[1]
        for method in required:
            if not methods.get(method, (0, 0))[0] & 0x100:
                raise ValueError(f"JNI native definition missing: {owner} {method}")


def validate_apk(apk: Path) -> None:
    classes = {}
    with zipfile.ZipFile(apk) as archive:
        dex = [info for info in archive.infolist()
               if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", info.filename)]
        if not dex:
            raise ValueError("no DEX files")
        for info in dex:
            if info.compress_type != zipfile.ZIP_DEFLATED:
                raise ValueError(f"Release DEX is not compressed: {info.filename}")
            classes.update(dex_definitions(archive.read(info)))
    validate_callbacks(classes)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", required=True, type=Path)
    args = parser.parse_args()
    apks = sorted(args.apk_dir.glob("*.apk"))
    errors = [] if apks else [f"no APKs found in {args.apk_dir}"]
    for apk in apks:
        try:
            validate_apk(apk)
            print(f"Verified Release optimization: {apk.name}")
        except (ValueError, IndexError, struct.error, zipfile.BadZipFile) as error:
            errors.append(f"{apk.name}: {error}")
    for error in errors:
        print(f"FAIL {error}")
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
