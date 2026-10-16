#!/usr/bin/env python3
"""Download pinned AOSP location Java/AIDL references, or verify them offline."""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
REFS = ROOT / "refs"
CACHE = ROOT / "build" / "android-refs-cache"
REPOSITORY = "https://android.googlesource.com/platform/frameworks/base"
VERSIONS = {
    "7.0.0_r1": "android-7.0.0_r1",
    "7.1.1_r6": "android-7.1.1_r6",
    "8.0.0_r4": "android-8.0.0_r4",
    "9.0.0_r3": "android-9.0.0_r3",
    **{f"{version}.0.0": f"android-{version}.0.0_r1" for version in range(10, 18)},
}
DIRECTORIES = (
    "location/java/android/location",
    "services/core/java/com/android/server/location",
)
EXTRA_FILES = (
    "services/core/java/com/android/server/LocationManagerService.java",
    "core/java/android/os/Build.java",
    "NOTICE",
)
INTERESTING_JAVA = {
    "Location.java", "LocationManager.java", "LocationRequest.java", "LocationResult.java",
    "LocationTime.java", "GnssStatus.java", "GnssMeasurement.java", "GnssMeasurementsEvent.java",
    "GnssNavigationMessage.java", "GnssStatusCallback.java", "GnssNmeaListener.java",
    "OnNmeaMessageListener.java", "LocationListener.java", "LocationProvider.java",
    "LocationPermissions.java", "LocationShellCommand.java", "LocationManagerService.java",
}


def request(url):
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Portal-Android-Refs/1"})
            with urllib.request.urlopen(req, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
        time.sleep(2 ** attempt)


def gitiles_json(revision, path="", optional=False):
    url = f"{REPOSITORY}/+/{revision}/{path}?format=JSON"
    try:
        return json.loads(request(url).decode("utf-8").removeprefix(")]}'\n"))
    except urllib.error.HTTPError as error:
        if optional and error.code == 404:
            return None
        raise


def git_blob_id(data):
    return hashlib.sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()


def destination(version, source_path):
    base = (REFS / version / "aosp").resolve()
    target = (base / source_path).resolve()
    if not target.is_relative_to(base) or "\\" in source_path:
        raise ValueError(f"Unsafe source path: {source_path}")
    return target


def collect_tree(commit, path, tree):
    files = {}
    for entry in tree["entries"]:
        name = entry["name"]
        if name in (".", "..") or "/" in name or "\\" in name:
            raise ValueError(f"Unsafe tree entry: {name}")
        child = f"{path.rstrip('/')}/{name}"
        if entry["type"] == "tree":
            files.update(collect_tree(commit, child, gitiles_json(commit, child + "/")))
        elif entry["type"] == "blob" and (name.endswith(".aidl") or name in INTERESTING_JAVA):
            files[child] = entry["id"]
    return files


def plan_version(version):
    tag = VERSIONS[version]
    revision_info = json.loads(request(f"{REPOSITORY}/+/refs/tags/{tag}?format=JSON").decode("utf-8").removeprefix(")]}'\n"))
    commit = revision_info["commit"]
    revision = f"refs/tags/{tag}"
    files = {}
    included = []
    for index, path in enumerate(DIRECTORIES):
        tree = gitiles_json(revision, path + "/", optional=index >= 2)
        if tree is not None:
            files.update(collect_tree(commit, path, tree))
            included.append(path + "/")
    for path in EXTRA_FILES:
        entry = gitiles_json(commit, path, optional=path != "core/java/android/os/Build.java")
        if entry is not None:
            files[path] = entry["id"]
            included.append(path)
    print(f"Indexed {version}: {len(files)} files at {commit}", flush=True)
    return {"version": version, "tag": tag, "commit": commit, "revision": revision, "scope": included, "files": files}


def download_blob(item):
    blob_id, (commit, path) = item
    if not re.fullmatch(r"[0-9a-f]{40}", blob_id):
        raise ValueError(f"Invalid Git blob ID: {blob_id}")
    cached = CACHE / blob_id
    if cached.exists():
        data = cached.read_bytes()
        if git_blob_id(data) == blob_id:
            return blob_id, data
    url = f"{REPOSITORY}/+/{commit}/{path}?format=TEXT"
    data = base64.b64decode(request(url), validate=True)
    if git_blob_id(data) != blob_id:
        raise ValueError(f"Git blob checksum mismatch: {path}")
    cached.write_bytes(data)
    return blob_id, data


def verify(versions):
    count = 0
    for version in versions:
        manifest_path = REFS / version / "sources.json"
        if not manifest_path.exists():
            print(f"Skipped {version}: no local snapshot", flush=True)
            continue
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest["tag"] != VERSIONS[version] or manifest["repository"] != REPOSITORY:
            raise ValueError(f"Unexpected source metadata: {version}")
        expected = set()
        for entry in manifest["files"]:
            target = destination(version, entry["path"])
            expected.add(target)
            data = target.read_bytes()
            if (len(data) != entry["size"] or git_blob_id(data) != entry["git_blob"]
                    or hashlib.sha256(data).hexdigest() != entry["sha256"]):
                raise ValueError(f"Snapshot checksum mismatch: {target}")
            count += 1
        actual = {path.resolve() for path in (REFS / version / "aosp").rglob("*") if path.is_file()}
        if actual != expected:
            raise ValueError(f"Snapshot file inventory mismatch: {version}")
        print(f"Verified {version}: {len(expected)} files", flush=True)
    print(f"PASS: {count} files across {len(versions)} versions verified offline.", flush=True)


def sync(versions, workers):
    with ThreadPoolExecutor(max_workers=workers) as pool:
        plans = list(pool.map(plan_version, versions))
    unique = {}
    for plan in plans:
        for path, blob_id in plan["files"].items():
            unique.setdefault(blob_id, (plan["revision"], path))
    CACHE.mkdir(parents=True, exist_ok=True)
    blobs = {}
    print(f"Fetching {len(unique)} unique source blobs...", flush=True)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        pending = [pool.submit(download_blob, item) for item in unique.items()]
        for future in as_completed(pending):
            blob_id, data = future.result()
            blobs[blob_id] = data
            if len(blobs) % 100 == 0 or len(blobs) == len(unique):
                print(f"Fetched {len(blobs)}/{len(unique)} source blobs", flush=True)
    # Check every destination before writing snapshots; preserve manually edited files.
    for plan in plans:
        for path, blob_id in plan["files"].items():
            target = destination(plan["version"], path)
            if target.exists() and target.read_bytes() != blobs[blob_id]:
                raise ValueError(f"Refusing to overwrite changed snapshot: {target}")
    for plan in plans:
        entries = []
        for path, blob_id in sorted(plan["files"].items()):
            data = blobs[blob_id]
            target = destination(plan["version"], path)
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                target.write_bytes(data)
            entries.append({"path": path, "git_blob": blob_id,
                            "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
        manifest = {"format_version": 1, "repository": REPOSITORY, "tag": plan["tag"],
                    "commit": plan["commit"], "retrieved_on": datetime.now(timezone.utc).date().isoformat(),
                    "scope": plan["scope"], "files": entries}
        manifest_path = REFS / plan["version"] / "sources.json"
        if manifest_path.exists():
            previous = json.loads(manifest_path.read_text(encoding="utf-8"))
            if all(previous.get(key) == value for key, value in manifest.items() if key != "retrieved_on"):
                manifest["retrieved_on"] = previous["retrieved_on"]
        manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"Saved {plan['version']}: {len(entries)} files", flush=True)
    verify(versions)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="append", choices=VERSIONS, help="Select a version; repeatable. Default: all.")
    parser.add_argument("--verify", action="store_true", help="Verify local manifests and files without network access.")
    parser.add_argument("--workers", type=int, default=6, help="Parallel network requests (1-12, default: 6).")
    args = parser.parse_args()
    if not 1 <= args.workers <= 12:
        parser.error("--workers must be between 1 and 12")
    versions = list(dict.fromkeys(args.version or VERSIONS))
    if args.verify:
        verify(versions)
    else:
        sync(versions, args.workers)


if __name__ == "__main__":
    main()
