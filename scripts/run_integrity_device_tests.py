"""Run instrumentation against a disposable Android emulator; reject silent runner failures."""
import argparse
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--screenshots", type=Path, default=Path("integrity-screenshots"))
    args = parser.parse_args()
    subprocess.run(["adb", "install", "-r", str(args.apk)], check=True, timeout=120)
    subprocess.run(["adb", "install", "-r", str(args.test_apk)], check=True, timeout=120)
    package = "io.github.lecheeel.locationintegrity.debug"
    result = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-r", package + ".test/androidx.test.runner.AndroidJUnitRunner"],
                            capture_output=True, text=True, timeout=240)
    print(result.stdout)
    if result.stderr:
        print(result.stderr)
    args.screenshots.mkdir(parents=True, exist_ok=True)
    subprocess.run(["adb", "pull", "/sdcard/Android/data/" + package + "/files/screenshots/.", str(args.screenshots)], check=False)
    if result.returncode or not re.search(r"OK [(][1-9][0-9]* tests?[)]", result.stdout) or "FAILURES!!!" in result.stdout:
        raise SystemExit("Android instrumentation did not complete successfully")
    if not list(args.screenshots.glob("*.png")):
        raise SystemExit("Instrumentation screenshots are missing")


if __name__ == "__main__":
    main()
