import tempfile
from pathlib import Path
import unittest
import zipfile
from verify_release_apks import RELEASE_CERT_SHA256, validate_signature, validate_manifest, validate_abis


class ReleaseVerificationTest(unittest.TestCase):
    def test_unsigned_or_wrong_signer_cannot_be_promoted(self):
        valid = f"Verified using v2 scheme (APK Signature Scheme v2): true\nSigner certificate SHA-256 digest: {RELEASE_CERT_SHA256}"
        validate_signature(valid)
        for output in ("", valid.replace(RELEASE_CERT_SHA256, "0" * 64), valid.replace(": true", ": false")):
            with self.assertRaises(ValueError):
                validate_signature(output)

    def test_mislabeled_apk_or_wrong_package_is_rejected(self):
        valid = "package: name='com.system.location.service' versionCode='1790000012' versionName='1.7.5'"
        validate_manifest(valid, "1.7.5", 1790000012)
        for output in (valid.replace("1.7.5", "1.7.4"), valid.replace("1790000012", "1790000011"), valid.replace("com.system.location.service", "other.package")):
            with self.assertRaises(ValueError):
                validate_manifest(output, "1.7.5", 1790000012)

    def test_native_architecture_must_match_the_asset_name(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "arm64.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/arm64-v8a/libtest.so", b"test")
                archive.writestr("assets/not-a-library", b"test")
            self.assertEqual(["arm64-v8a"], validate_abis(apk, {"arm64-v8a"}))
            with self.assertRaises(ValueError):
                validate_abis(apk, {"x86_64"})


if __name__ == "__main__":
    unittest.main()
