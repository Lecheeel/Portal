#!/usr/bin/env python3
"""Syntax-check the native hook sources for all packaged ABIs without invoking CMake.

This is a fast CI/local guard for P4-04. It does not link or run the library; the full
CMake build and on-device sensor validation remain separate requirements.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ("main.cpp", "sensor_hook.cpp")
LINK_SOURCES = ("main.cpp", "elf_util.cpp", "sensor_hook.cpp")
ABIS = {
    "arm64-v8a": "aarch64-linux-android31",
    "armeabi-v7a": "armv7a-linux-androideabi31",
    "x86": "i686-linux-android31",
    "x86_64": "x86_64-linux-android31",
}


def find_ndk() -> Path:
    candidates = []
    for env in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        if os.environ.get(env):
            candidates.append(Path(os.environ[env]))
    for env in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(env):
            candidates.extend(sorted((Path(os.environ[env]) / "ndk").glob("*"), reverse=True))
    local = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "ndk"
    if local.is_dir():
        candidates.extend(sorted(local.glob("*"), reverse=True))
    for candidate in candidates:
        if (candidate / "toolchains" / "llvm").is_dir():
            return candidate
    raise SystemExit("NDK not found; set ANDROID_NDK_HOME")


def find_clang(ndk: Path) -> Path:
    pattern = "toolchains/llvm/prebuilt/*/bin/clang++*"
    matches = sorted(ndk.glob(pattern))
    if not matches:
        raise SystemExit(f"clang++ not found under {ndk}")
    return matches[0]


def find_dobby_include() -> Path:
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-home"))
    aars = sorted(gradle_home.glob("caches/modules-2/files-2.1/io.github.vvb2060.ndk/dobby/**/*.aar"))
    if not aars:
        raise SystemExit("dobby AAR not found; resolve :xposed dependencies first")
    temp_root = ROOT / ".tmp"
    temp_root.mkdir(exist_ok=True)
    temp = temp_root / "dobby-prefab"
    if temp.exists():
        shutil.rmtree(temp)
    temp.mkdir(parents=True)
    with zipfile.ZipFile(aars[-1]) as archive:
        for name in archive.namelist():
            if name.startswith("prefab/modules/dobby/"):
                archive.extract(name, temp)
    include = temp / "prefab" / "modules" / "dobby" / "include"
    if not include.is_dir():
        raise SystemExit("dobby prefab include not found")
    return include


def main() -> int:
    link = "--link" in sys.argv[1:]
    ndk = find_ndk()
    clang = find_clang(ndk)
    sysroot = clang.parents[1] / "sysroot"
    include = find_dobby_include()
    cpp_dir = ROOT / "xposed" / "src" / "main" / "cpp"
    failures = 0
    for abi, target in ABIS.items():
        if link:
            output_dir = ROOT / ".tmp" / "native-link"
            output_dir.mkdir(parents=True, exist_ok=True)
            output = output_dir / f"liblocationext-{abi}.so"
            command = [
                str(clang), f"--target={target}", f"--sysroot={sysroot}",
                "-std=c++23", "-shared", "-fPIC", "-O2",
                "-I", str(cpp_dir), "-I", str(include),
                *[str(cpp_dir / source) for source in LINK_SOURCES],
                str(include.parent / "libs" / f"android.{abi}" / "libdobby.a"),
                "-llog", "-landroid", "-o", str(output),
            ]
            result = subprocess.run(command, capture_output=True, text=True)
            if result.returncode == 0 and output.is_file():
                print(f"OK link {abi} ({output.stat().st_size} bytes)")
            else:
                failures += 1
                print(f"FAIL link {abi}")
                print((result.stderr or result.stdout)[:2000])
            continue
        for source in SOURCES:
            command = [
                str(clang), f"--target={target}", f"--sysroot={sysroot}",
                "-std=c++23", "-fsyntax-only",
                "-I", str(cpp_dir), "-I", str(include), str(cpp_dir / source),
            ]
            result = subprocess.run(command, capture_output=True, text=True)
            if result.returncode == 0:
                print(f"OK {abi} {source}")
            else:
                failures += 1
                print(f"FAIL {abi} {source}")
                print((result.stderr or result.stdout)[:2000])
    mode = "native link" if link else "native syntax"
    print(f"{mode}: {failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
