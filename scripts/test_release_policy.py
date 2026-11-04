import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from release_policy import (
    ReleasePolicyError,
    ensure_version_code_is_newer,
    parse_release_codes,
    validate_promotion_bundle,
    validate_version,
)
from promote_release import main as promotion_main


class ReleasePolicyTest(unittest.TestCase):
    def test_version_names_allow_stable_and_supported_prerelease_channels(self):
        for version in ("1.7.5", "1.7.5-beta.1", "1.7.5-canary.2", "1.7.5-rc.3"):
            with self.subTest(version=version):
                self.assertEqual(version, validate_version(version))
        for version in ("1.7.5-dev.1", "1.7.5-beta", "1.7.5-rc.0-evil", "1.7.5;echo"):
            with self.subTest(version=version), self.assertRaises(ReleasePolicyError):
                validate_version(version)

    def test_new_build_must_exceed_all_known_published_codes(self):
        ensure_version_code_is_newer(1790000014, [1790000012, None, 1790000013])
        with self.assertRaises(ReleasePolicyError):
            ensure_version_code_is_newer(1790000013, [1790000012, 1790000013])

    def test_legacy_missing_metadata_is_explicit_but_present_bad_metadata_fails_closed(self):
        valid = json.dumps({"version": "1.8.0-beta.1", "versionCode": 1790000020,
                            "certificateSha256": "fa4547514e795b2daca48bd05e9a5f9348e62531d791d746e0ffe93bb90247a3"})
        releases = [
            {"tag_name": "v1.7.5", "assets": []},
            {"tag_name": "v1.8.0-beta.1", "assets": [{"name": "verification.json", "content": valid}]},
        ]
        self.assertEqual([1790000020], parse_release_codes(releases))
        releases[1]["assets"][0]["content"] = "{}"
        with self.assertRaises(ReleasePolicyError):
            parse_release_codes(releases)

    def test_remote_promotion_execute_flag_is_explicitly_unavailable(self):
        with patch("sys.argv", ["promote_release.py", "--execute"]), self.assertRaises(SystemExit) as error:
            promotion_main()
        self.assertEqual(2, error.exception.code)

    def test_promotion_requires_three_consistent_signed_artifacts_and_hash_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            artifacts = []
            for name, abis in (
                ("LocationService-v1.8.0-beta.1-all.apk", ["arm64-v8a", "x86_64"]),
                ("LocationService-v1.8.0-beta.1-arm64.apk", ["arm64-v8a"]),
                ("LocationService-v1.8.0-beta.1-x86_64.apk", ["x86_64"]),
            ):
                content = name.encode()
                with zipfile.ZipFile(directory / name, "w") as archive:
                    for abi in abis:
                        archive.writestr(f"lib/{abi}/native.so", content)
                content = (directory / name).read_bytes()
                artifacts.append({"file": name, "sha256": hashlib.sha256(content).hexdigest(), "abis": abis, "bytes": len(content)})
            report = {
                "version": "1.8.0-beta.1",
                "versionCode": 1790000020,
                "channel": "beta",
                "certificateSha256": "fa4547514e795b2daca48bd05e9a5f9348e62531d791d746e0ffe93bb90247a3",
                "artifacts": artifacts,
            }
            (directory / "verification.json").write_text(json.dumps(report), encoding="utf-8")
            signature = f"Verified using v2 scheme (APK Signature Scheme v2): true\nSigner certificate SHA-256 digest: {report['certificateSha256']}"
            manifest = "package: name='com.system.location.service' versionCode='1790000020' versionName='1.8.0-beta.1'"
            def fake_run(command, **kwargs):
                output = signature if "apksigner" in " ".join(command) else manifest
                return type("Result", (), {"stdout": output})()
            with patch.dict("os.environ", {"ANDROID_HOME": tmp}), patch("release_policy.subprocess.run", side_effect=fake_run):
                self.assertEqual(report, validate_promotion_bundle(directory))
            report["version"] = "1.8.0"
            (directory / "verification.json").write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaises(ReleasePolicyError):
                validate_promotion_bundle(directory)
            report["version"] = "1.8.0-beta.1"
            artifacts[0]["sha256"] = "0" * 64
            (directory / "verification.json").write_text(json.dumps(report), encoding="utf-8")
            with self.assertRaises(ReleasePolicyError):
                with patch.dict("os.environ", {"ANDROID_HOME": tmp}), patch("release_policy.subprocess.run", side_effect=fake_run):
                    validate_promotion_bundle(directory)


if __name__ == "__main__":
    unittest.main()
