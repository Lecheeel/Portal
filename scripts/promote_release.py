"""Validate an already verified Beta bundle for same-binary stable promotion."""
import argparse
import json
from pathlib import Path

from release_policy import ReleasePolicyError, validate_promotion_bundle


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("app/build/outputs/distribution"))
    parser.add_argument("--execute", action="store_true", help="reserved; remote writes are intentionally not implemented")
    args = parser.parse_args()
    if args.execute:
        parser.error("remote promotion is not implemented by this local-only tool")
    try:
        report = validate_promotion_bundle(args.directory)
    except ReleasePolicyError as exc:
        parser.error(str(exc))
    print(json.dumps({"mode": "dry-run", "same_apk": True, "version": report["version"], "versionCode": report["versionCode"], "release_prerelease_target": False, "make_latest_target": True}, indent=2))


if __name__ == "__main__":
    main()
