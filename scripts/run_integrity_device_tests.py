"""Run instrumentation against a disposable Android emulator; reject silent runner failures."""
import argparse
from pathlib import Path
import re
import subprocess


def failure_diagnostics(directory):
    for filename, command in (
        ("failure.png", ["adb", "exec-out", "screencap", "-p"]),
        ("window.txt", ["adb", "shell", "dumpsys", "window"]),
        ("logcat.txt", ["adb", "logcat", "-d", "-t", "1000"]),
    ):
        try:
            with (directory / filename).open("wb") as output:
                subprocess.run(command, stdout=output, stderr=subprocess.DEVNULL, check=False, timeout=15)
        except (OSError, subprocess.TimeoutExpired) as error:
            print("Could not collect", filename, type(error).__name__)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--screenshots", type=Path, default=Path("integrity-screenshots"))
    args = parser.parse_args()
    subprocess.run(["adb", "install", "-r", str(args.apk)], check=True, timeout=120)
    subprocess.run(["adb", "install", "-r", str(args.test_apk)], check=True, timeout=120)
    # A boot-complete emulator can still have a locked/sleeping window above the app.
    # These commands configure only the disposable CI device, never the tested app.
    for command in (
        ["settings", "put", "system", "screen_off_timeout", "1800000"],
        ["svc", "power", "stayon", "true"],
        ["input", "keyevent", "KEYCODE_WAKEUP"],
        ["wm", "dismiss-keyguard"],
    ):
        subprocess.run(["adb", "shell", *command], check=True, timeout=15)
    package = "io.github.lecheeel.locationintegrity.debug"
    args.screenshots.mkdir(parents=True, exist_ok=True)
    try:
        result = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-r", package + ".test/androidx.test.runner.AndroidJUnitRunner"],
                                capture_output=True, text=True, timeout=240)
    except subprocess.TimeoutExpired:
        failure_diagnostics(args.screenshots)
        raise SystemExit("Android instrumentation timed out") from None
    print(result.stdout)
    if result.stderr:
        print(result.stderr)
    subprocess.run(["adb", "pull", "/sdcard/Android/data/" + package + "/files/screenshots/.", str(args.screenshots)], check=False, timeout=30)
    if result.returncode or not re.search(r"OK [(][1-9][0-9]* tests?[)]", result.stdout) or "FAILURES!!!" in result.stdout:
        failure_diagnostics(args.screenshots)
        raise SystemExit("Android instrumentation did not complete successfully")
    if not list(args.screenshots.glob("*.png")):
        raise SystemExit("Instrumentation screenshots are missing")


if __name__ == "__main__":
    main()
