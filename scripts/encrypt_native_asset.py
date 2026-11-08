#!/usr/bin/env python3
"""Encrypt a stripped liblocationext.so into the app's opaque native asset.

Run after CMake/NDK build and before packaging. The key must stay in sync with
NativeLibraryInstaller.KEY.
"""
from __future__ import annotations

import argparse
from pathlib import Path

KEY = bytes((0xA7, 0x3C, 0x91, 0x5E, 0xD2, 0x44, 0xBB, 0x6F))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("library", type=Path)
    parser.add_argument("asset", type=Path)
    args = parser.parse_args()
    data = args.library.read_bytes()
    if len(data) < 64:
        raise SystemExit("native library is too small")
    args.asset.parent.mkdir(parents=True, exist_ok=True)
    args.asset.write_bytes(bytes(value ^ KEY[index % len(KEY)] for index, value in enumerate(data)))
    print(f"encrypted {len(data)} bytes -> {args.asset}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
