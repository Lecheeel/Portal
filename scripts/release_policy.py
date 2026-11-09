"""Shared, fail-closed policy for Portal release builds and local promotion checks."""
import hashlib
import json
from pathlib import Path
import re
import urllib.error
import urllib.request
import subprocess
import os
import zipfile
import time


def open_release_url(request):
    """Retry transient GitHub/CDN failures without relaxing release identity checks."""
    for attempt in range(3):
        try:
            return urllib.request.urlopen(request, timeout=20)
        except urllib.error.HTTPError as exc:
            if exc.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(1 + attempt)
    raise RuntimeError("Release request retry exhausted")

RELEASE_CERT_SHA256 = "fa4547514e795b2daca48bd05e9a5f9348e62531d791d746e0ffe93bb90247a3"
VERSION_PATTERN = re.compile(r"\d+\.\d+\.\d+(?:-(?:beta|canary|rc)\.\d+)?\Z")
ARTIFACTS = {
    "all": ["arm64-v8a", "x86_64"],
    "arm64": ["arm64-v8a"],
    "x86_64": ["x86_64"],
}


class ReleasePolicyError(ValueError):
    pass


def validate_version(version):
    if not isinstance(version, str) or not VERSION_PATTERN.fullmatch(version):
        raise ReleasePolicyError("version must be major.minor.patch with optional -beta.N, -canary.N, or -rc.N")
    return version


def validate_version_code(version_code):
    if isinstance(version_code, bool) or not isinstance(version_code, int) or not 1 <= version_code <= 2_100_000_000:
        raise ReleasePolicyError("version code must be an integer from 1 through 2100000000")
    return version_code


def ensure_version_code_is_newer(version_code, known_codes):
    validate_version_code(version_code)
    codes = [validate_version_code(code) for code in known_codes if code is not None]
    if codes and version_code <= max(codes):
        raise ReleasePolicyError(f"version code {version_code} must exceed highest known published code {max(codes)}")


def parse_release_codes(releases):
    """Return known codes, explicitly ignoring only absent legacy verification assets."""
    codes = []
    for release in releases:
        if release.get("draft"):
            continue
        assets = release.get("assets", [])
        if not isinstance(assets, list):
            raise ReleasePolicyError(f"invalid assets list for {release.get('tag_name', '<untagged>')}")
        metadata_assets = [a for a in assets if a.get("name") == "verification.json"]
        if len(metadata_assets) > 1:
            raise ReleasePolicyError(f"duplicate verification.json assets for {release.get('tag_name', '<untagged>')}")
        asset = metadata_assets[0] if metadata_assets else None
        if asset is None:
            print(f"Legacy release {release.get('tag_name', '<untagged>')}: no verification.json; code unknown")
            continue
        try:
            data = json.loads(asset["content"])
            code = validate_version_code(data.get("versionCode"))
            if not isinstance(data.get("certificateSha256"), str) or data["certificateSha256"].lower() != RELEASE_CERT_SHA256:
                raise ReleasePolicyError("certificate mismatch")
            validate_version(data.get("version"))
        except (KeyError, TypeError, json.JSONDecodeError, ReleasePolicyError, AttributeError) as exc:
            raise ReleasePolicyError(f"invalid verification.json for {release.get('tag_name', '<untagged>')}: {exc}") from exc
        codes.append(code)
    return codes


def fetch_published_codes(repository="Lecheeel/Portal"):
    try:
        releases = []
        page = 1
        while True:
            request = urllib.request.Request(
                f"https://api.github.com/repos/{repository}/releases?per_page=100&page={page}",
                headers={"Accept": "application/vnd.github+json", "User-Agent": "Portal-release-policy"},
            )
            with open_release_url(request) as response:
                batch = json.load(response)
            if not isinstance(batch, list):
                raise ReleasePolicyError("GitHub releases response is not a list")
            releases.extend(batch)
            if len(batch) < 100:
                break
            page += 1
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise ReleasePolicyError(f"could not read published releases: {exc}") from exc
    # Fetch metadata assets individually; never infer a code from release tags.
    for release in releases:
        if release.get("draft"):
            continue
        for asset in release.get("assets", []):
            if asset.get("name") == "verification.json":
                download_url = asset.get("browser_download_url")
                if not download_url:
                    raise ReleasePolicyError(f"verification.json URL missing for {release.get('tag_name')}")
                req = urllib.request.Request(download_url, headers={"User-Agent": "Portal-release-policy"})
                try:
                    with open_release_url(req) as response:
                        asset["content"] = response.read().decode("utf-8")
                except (urllib.error.URLError, TimeoutError, UnicodeDecodeError) as exc:
                    raise ReleasePolicyError(f"could not read verification.json for {release.get('tag_name')}: {exc}") from exc
    return parse_release_codes(releases)


def validate_promotion_bundle(directory):
    """Verify local artifact identity and hashes before a same-binary promotion dry-run."""
    directory = Path(directory)
    try:
        report = json.loads((directory / "verification.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ReleasePolicyError(f"missing or invalid verification.json: {exc}") from exc
    version = validate_version(report.get("version"))
    if "-" not in version:
        raise ReleasePolicyError("same-binary promotion requires a prerelease Beta/canary/rc build")
    code = validate_version_code(report.get("versionCode"))
    if report.get("channel") not in {"beta", "stable"}:
        raise ReleasePolicyError("verification.json must declare stable or beta channel")
    if not isinstance(report.get("certificateSha256"), str) or report["certificateSha256"].lower() != RELEASE_CERT_SHA256:
        raise ReleasePolicyError("verification.json certificate does not match the established release signer")
    expected = {f"LocationService-v{version}-{abi}.apk" for abi in ARTIFACTS}
    actual = {p.name for p in directory.glob("*.apk")}
    if actual != expected:
        raise ReleasePolicyError("promotion bundle must contain exactly the three APKs named by verification.json")
    entries = report.get("artifacts")
    if not isinstance(entries, list) or {entry.get("file") for entry in entries if isinstance(entry, dict)} != expected:
        raise ReleasePolicyError("verification.json artifact list is incomplete or inconsistent")
    for entry in entries:
        path = directory / entry["file"]
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        flavor = entry["file"].rsplit("-", 1)[-1][:-4]
        if entry.get("sha256") != digest or entry.get("bytes") != path.stat().st_size:
            raise ReleasePolicyError(f"artifact hash or size mismatch: {path.name}")
        if entry.get("abis") != ARTIFACTS[flavor]:
            raise ReleasePolicyError(f"artifact ABI metadata mismatch: {path.name}")
        with zipfile.ZipFile(path) as archive:
            actual_abis = sorted({name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")})
        if actual_abis != ARTIFACTS[flavor]:
            raise ReleasePolicyError(f"APK ABI contents mismatch: {path.name}")
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise ReleasePolicyError("ANDROID_HOME or ANDROID_SDK_ROOT is required to validate signed APKs")
        tools = Path(sdk) / "build-tools" / "37.0.0"
        signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
        aapt = tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
        def run_tool(tool, *args):
            command = [str(tool), *map(str, args)]
            if os.name == "nt" and tool.suffix == ".bat":
                command = ["cmd.exe", "/c", *command]
            return subprocess.run(command, check=True, capture_output=True, text=True).stdout
        signature = run_tool(signer, "verify", "--verbose", "--print-certs", path)
        certificates = {value.lower() for value in re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", signature)}
        if certificates != {RELEASE_CERT_SHA256} or "Verified using v2 scheme (APK Signature Scheme v2): true" not in signature:
            raise ReleasePolicyError(f"APK does not have the established v2 release signature: {path.name}")
        manifest = run_tool(aapt, "dump", "badging", path)
        package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", manifest)
        if not package or package.groups() != ("com.system.location.service", str(code), version):
            raise ReleasePolicyError(f"APK package or version mismatch: {path.name}")
    return report
