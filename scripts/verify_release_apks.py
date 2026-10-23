"""Fail closed on unsigned, incompatible, mislabeled or incorrectly packaged releases."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

# Public certificate SHA-256 verified from the published v1.7.4 arm64 APK.
RELEASE_CERT_SHA256 = "fa4547514e795b2daca48bd05e9a5f9348e62531d791d746e0ffe93bb90247a3"
ABIS = {"all": {"arm64-v8a", "x86_64"}, "arm64": {"arm64-v8a"}, "x86_64": {"x86_64"}}


def validate_signature(output):
    certificates = set(re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", output))
    if {value.lower() for value in certificates} != {RELEASE_CERT_SHA256}:
        raise ValueError("APK signer differs from the established release certificate")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in output:
        raise ValueError("APK Signature Scheme v2 verification is required")


def validate_manifest(output, version, version_code):
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output)
    if not package or package.groups() != ("com.system.location.service", str(version_code), version):
        raise ValueError("APK package or version does not match this release")


def validate_abis(apk, expected):
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
    if abis != expected:
        raise ValueError(f"{apk.name}: unexpected native ABIs {sorted(abis)}, expected {sorted(expected)}")
    return sorted(abis)


def run_tool(path, *args):
    command = [str(path), *map(str, args)]
    if os.name == "nt" and path.suffix == ".bat":
        command = ["cmd.exe", "/c", *command]
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--directory", type=Path, default=Path("app/build/outputs/distribution"))
    args = parser.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        parser.error("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    tools = Path(sdk) / "build-tools" / "37.0.0"
    expected = {f"LocationService-v{args.version}-{flavor}.apk": abis for flavor, abis in ABIS.items()}
    if {apk.name for apk in args.directory.glob("*.apk")} != set(expected):
        raise ValueError("Distribution must contain exactly the three APKs for this release")
    artifacts = []
    for name, abis in expected.items():
        apk = args.directory / name
        signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
        aapt = tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
        validate_signature(run_tool(signer, "verify", "--verbose", "--print-certs", apk))
        validate_manifest(run_tool(aapt, "dump", "badging", apk), args.version, args.version_code)
        actual_abis = validate_abis(apk, abis)
        with apk.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        artifacts.append({"file": name, "sha256": digest, "abis": actual_abis, "bytes": apk.stat().st_size})
        print(f"Verified {name}: release signature, version, ABIs and SHA-256")
    report = {"version": args.version, "versionCode": args.version_code, "certificateSha256": RELEASE_CERT_SHA256, "artifacts": artifacts}
    (args.directory / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (args.directory / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['file']}\n" for item in artifacts), encoding="utf-8")


if __name__ == "__main__":
    main()
