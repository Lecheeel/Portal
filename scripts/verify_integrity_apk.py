"""Verify the independent APK, permission surface, signing identity and release metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

PACKAGE = "io.github.lecheeel.locationintegrity"
ALLOWED = {"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
           "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_LOCATION",
           "android.permission.POST_NOTIFICATIONS", PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}


def verify_certificate_output(text, pin):
    # Build-tools 37 can label signers "V2 Signer" instead of "Signer #1".
    digests = re.findall("certificate SHA-256 digest: *([a-fA-F0-9]{64})", text)
    if "Number of signers: 1" not in text or not digests or {value.lower() for value in digests} != {pin.lower()}:
        raise ValueError("APK signing certificate does not match the pinned release identity")
    return digests[0].lower()


def inspect_badging(text, permissions, version="0.1.0"):
    match = re.search("package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text)
    if not match or match.group(1) != PACKAGE or match.group(2) != "1" or match.group(3) != version:
        raise ValueError("Unexpected package identity/version")
    if "sdkVersion:'26'" not in text or "targetSdkVersion:'37'" not in text:
        raise ValueError("Unexpected supported Android SDK range")
    if "application-debuggable" in text:
        raise ValueError("Release APK must not be debuggable")
    requested = set(re.findall("uses-permission[^:]*: name='([^']+)'", permissions))
    if not requested <= ALLOWED:
        raise ValueError("Unexpected permissions: " + str(sorted(requested - ALLOWED)))
    if not {"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"} <= requested:
        raise ValueError("Location observation permissions are missing")
    return sorted(requested)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--unsigned", action="store_true")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/SDK")))))
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    tools = args.sdk / "build-tools" / "37.0.0"
    suffix = ".exe" if os.name == "nt" else ""
    aapt = str(tools / ("aapt" + suffix))
    def aapt_dump(kind):
        return subprocess.check_output([aapt, "dump", kind, str(args.apk)], text=True, encoding="utf-8")
    permissions = inspect_badging(aapt_dump("badging"), aapt_dump("permissions"))
    with zipfile.ZipFile(args.apk) as archive:
        names = archive.namelist()
        if any(name.startswith("lib/") and name.endswith(".so") for name in names):
            raise ValueError("Unexpected native library in the observation APK")
        for name in names:
            if name.endswith(".dex"):
                dex = archive.read(name)
                if b"de/robv/android/xposed/" in dex or b"io/github/libxposed/" in dex:
                    raise ValueError("Xposed code must not be bundled in the detector")
    fingerprint = None
    if not args.unsigned:
        executable = str(tools / ("apksigner.bat" if os.name == "nt" else "apksigner"))
        result = subprocess.check_output([executable, "verify", "--verbose", "--print-certs", str(args.apk)], text=True)
        if "Verified using v2 scheme (APK Signature Scheme v2): true" not in result:
            raise ValueError("APK Signature Scheme v2 verification failed")
        pin = (Path(__file__).resolve().parents[1] / "integrity-app/release-certificate.sha256").read_text().strip()
        fingerprint = verify_certificate_output(result, pin)
    result = {"package": PACKAGE, "versionName": "0.1.0", "versionCode": 1, "minSdk": 26, "targetSdk": 37,
              "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(), "certificateSha256": fingerprint,
              "permissions": permissions, "nativeLibraries": 0, "xposedBundled": False, "bytes": args.apk.stat().st_size}
    encoded = json.dumps(result, indent=2)
    if args.out:
        args.out.write_text(encoded + chr(10), encoding="utf-8")
    print(encoded)


if __name__ == "__main__":
    main()
