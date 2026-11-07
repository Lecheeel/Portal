#!/usr/bin/env python3
"""Deploy the Portal debug APK to a Vector (libxposed) device.

Two modes:
  --hot-reload  install, start the module app so its libxposed service binds, and
                wait for Vector's automatic system_server hot reload (no reboot).
  default       install, refresh Vector's cached APK path, then full reboot.
"""
from __future__ import annotations

import argparse
import os
import shutil
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.system.location.service"
VECTOR_CLI = "/data/adb/modules/zygisk_vector/cli"
DB = "/data/adb/lspd/config/modules_config.db"
TMP_DB = "/data/local/tmp/portal-modules.db"
SCOPES = [
    "system/0",
    "com.android.phone/0",
    "com.android.location.fused/0",
    "com.xiaomi.location.fused/0",
    "com.android.providers.settings/0",
]


def find_adb() -> str:
    for candidate in (
        os.environ.get("ADB"),
        shutil.which("adb"),
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe",
    ):
        if candidate and Path(candidate).exists():
            return str(candidate)
    raise SystemExit("adb not found; set ADB or put adb on PATH")


class Device:
    def __init__(self, adb: str, serial: str | None):
        self.base = [adb] + (["-s", serial] if serial else [])

    def run(self, *args: str, check: bool = True, timeout: int = 300) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [*self.base, *args], check=check, text=True, capture_output=True, timeout=timeout
        )

    def shell(self, command: str, check: bool = True) -> str:
        return self.run("shell", command, check=check).stdout.strip()

    def root(self, command: str, check: bool = True) -> str:
        return self.shell(f"su -c {shlex_quote(command)}", check=check)

    def wait_boot(self, timeout: int = 180) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.shell("getprop sys.boot_completed", check=False) == "1":
                return
            time.sleep(2)
        raise SystemExit("device did not finish booting")


def shlex_quote(value: str) -> str:
    return "'" + value.replace("'", "'\\''") + "'"


def update_db(device: Device, apk_path: str, package: str) -> None:
    tmp = Path(".tmp") / "portal-vector-deploy"
    tmp.mkdir(parents=True, exist_ok=True)
    local = tmp / "modules.db"
    local.unlink(missing_ok=True)
    try:
        device.root(f"cp {DB} {TMP_DB}; chmod 644 {TMP_DB}")
        device.run("pull", TMP_DB, str(local))
        connection = sqlite3.connect(local)
        try:
            row = connection.execute(
                "select mid from modules where module_pkg_name=?", (package,)
            ).fetchone()
            if row is None:
                raise SystemExit(f"module {package} not found in Vector database")
            connection.execute("update modules set apk_path=? where mid=?", (apk_path, row[0]))
            connection.commit()
        finally:
            connection.close()
        device.run("push", str(local), TMP_DB)
        device.root(
            f"cp {TMP_DB} {DB}; chown root:root {DB}; chmod 660 {DB}; "
            f"rm -f {DB}-wal {DB}-shm"
        )
    finally:
        local.unlink(missing_ok=True)


def ensure_scope(device: Device, package: str) -> None:
    listing = device.root(f"{VECTOR_CLI} scope ls {package}", check=False)
    if "system" not in listing.split():
        device.root(f"{VECTOR_CLI} scope add {package} system/0")
        print("added system/0 scope")


def hot_reload(device: Device, package: str) -> None:
    device.shell("am force-stop " + package, check=False)
    time.sleep(2)
    device.shell(f"am start -W -n {package}/.MainActivity", check=False)
    # The XposedProvider starts the app process and binds the libxposed service; autoHotReload
    # then updates system_server without changing its pid.
    time.sleep(12)
    pid_before = device.shell("pidof system_server", check=False)
    log = device.root("ls -t /data/adb/lspd/log/modules_*.log | head -1", check=False)
    if log:
        tail = device.root(f"grep -i 'hot reloaded\\|hot reloading' {log} | tail -5", check=False)
        if tail:
            print(tail)
    print(f"system_server pid: {pid_before}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="debug APK to install")
    parser.add_argument("--adb", default=None)
    parser.add_argument("--serial", default=None)
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--hot-reload", action="store_true", help="install and trigger hot reload; no reboot")
    parser.add_argument("--soft-restart", action="store_true", help="restart zygote instead of a full reboot")
    args = parser.parse_args()

    if not args.apk.is_file():
        raise SystemExit(f"APK not found: {args.apk}")

    device = Device(args.adb or find_adb(), args.serial)
    print(f"installing {args.apk}")
    device.run("install", "-r", str(args.apk))
    apk_path = device.shell(f"pm path {args.package}").splitlines()[0].removeprefix("package:").strip()
    if not apk_path:
        raise SystemExit("package manager returned no APK path")
    print(f"current apk: {apk_path}")

    update_db(device, apk_path, args.package)
    ensure_scope(device, args.package)

    if args.hot_reload:
        hot_reload(device, args.package)
        return 0

    if args.soft_restart:
        device.root("setprop ctl.restart zygote")
    else:
        device.run("reboot")
    time.sleep(8)
    device.run("wait-for-device", timeout=180)
    device.wait_boot()
    print("device ready")
    return 0


if __name__ == "__main__":
    sys.exit(main())
