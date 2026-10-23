import unittest
from verify_integrity_apk import inspect_badging, PACKAGE


class IntegrityApkVerificationTest(unittest.TestCase):
    def good(self):
        return ("package: name='" + PACKAGE + "' versionCode='1' versionName='0.1.0'" + chr(10) +
                "sdkVersion:'26'" + chr(10) + "targetSdkVersion:'37'")
    def permissions(self):
        return "uses-permission: name='android.permission.ACCESS_FINE_LOCATION'" + chr(10) + "uses-permission: name='android.permission.ACCESS_COARSE_LOCATION'"
    def test_accepts_observation_only_manifest(self):
        self.assertEqual(len(inspect_badging(self.good(), self.permissions())), 2)
    def test_network_permission_fails_release_gate(self):
        with self.assertRaises(ValueError):
            inspect_badging(self.good(), self.permissions() + chr(10) + "uses-permission: name='android.permission.INTERNET'")
    def test_mock_permission_fails_release_gate(self):
        with self.assertRaises(ValueError):
            inspect_badging(self.good(), self.permissions() + chr(10) + "uses-permission: name='android.permission.ACCESS_MOCK_LOCATION'")
    def test_debuggable_release_fails(self):
        with self.assertRaises(ValueError):
            inspect_badging(self.good() + chr(10) + "application-debuggable", self.permissions())
    def test_wrong_package_fails(self):
        with self.assertRaises(ValueError):
            inspect_badging(self.good().replace(PACKAGE, "com.system.location.service"), self.permissions())


if __name__ == "__main__":
    unittest.main()
