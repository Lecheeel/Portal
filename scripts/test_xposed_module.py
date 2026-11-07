import struct
import tempfile
from pathlib import Path
import unittest
import zipfile

from check_xposed_module import (
    ENTRY, PROPERTIES, RESOURCE_DIR, SCOPES, dex_class_descriptors,
    source_violations, validate_apk, validate_metadata,
)


def metadata():
    return {
        "java_init.list": f"{ENTRY}\n".encode(),
        "module.prop": "".join(f"{key}={value}\n" for key, value in PROPERTIES.items()).encode(),
        "scope.list": "\n".join(sorted(SCOPES)).encode(),
    }


def dex(definitions, references=()):
    """Build index tables with separate type references and class definitions."""
    types = list(dict.fromkeys([*definitions, *references]))
    string_offset = 112
    type_offset = string_offset + len(types) * 4
    class_offset = type_offset + len(types) * 4
    result = bytearray(class_offset + len(definitions) * 32)
    result[:8] = b"dex\n039\0"
    for offset, value in ((56, len(types)), (60, string_offset), (64, len(types)),
                          (68, type_offset), (96, len(definitions)), (100, class_offset)):
        struct.pack_into("<I", result, offset, value)
    for index, descriptor in enumerate(types):
        struct.pack_into("<I", result, string_offset + index * 4, len(result))
        struct.pack_into("<I", result, type_offset + index * 4, index)
        # The parser skips this ULEB length; test descriptors fit in one byte.
        result.extend(bytes([len(descriptor)]) + descriptor.encode() + b"\0")
    for index, descriptor in enumerate(definitions):
        struct.pack_into("<I", result, class_offset + index * 32, types.index(descriptor))
    return bytes(result)


ENTRY_DESCRIPTOR = f"L{ENTRY.replace('.', '/')};"
API_DESCRIPTOR = "Lio/github/libxposed/api/XposedModule;"


class XposedModuleVerificationTest(unittest.TestCase):
    def apk(self, directory, resources=None, classes=None, extra=None, manifest=None):
        path = Path(directory) / "portal.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in (metadata() if resources is None else resources).items():
                archive.writestr(f"META-INF/xposed/{name}", data)
            archive.writestr("AndroidManifest.xml", manifest or b"<manifest><application/></manifest>")
            archive.writestr("classes.dex", classes if classes is not None else dex([ENTRY_DESCRIPTOR], [API_DESCRIPTOR]))
            for name, data in (extra or {}).items():
                archive.writestr(name, data)
        return path

    def test_valid_apk_can_reference_framework_api_without_packaging_it(self):
        with tempfile.TemporaryDirectory() as directory:
            validate_apk(self.apk(directory))

    def test_missing_entry_resource_is_rejected(self):
        for name in metadata():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                resources = metadata()
                del resources[name]
                with self.assertRaisesRegex(ValueError, "missing or duplicate"):
                    validate_apk(self.apk(directory, resources=resources))

    def test_incorrect_api_scope_and_hot_reload_are_rejected(self):
        changes = (
            ("module.prop", b"minApiVersion=102", b"minApiVersion=101"),
            ("module.prop", b"targetApiVersion=102", b"targetApiVersion=101"),
            ("module.prop", b"autoHotReload=true", b"autoHotReload=false"),
            ("module.prop", b"staticScope=true", b"staticScope=false"),
            ("scope.list", b"system", b"android"),
            ("java_init.list", ENTRY.encode(), b"example.WrongModule"),
        )
        for name, old, new in changes:
            with self.subTest(name=name, change=new):
                resources = metadata()
                resources[name] = resources[name].replace(old, new)
                with self.assertRaises(ValueError):
                    validate_metadata(resources)

    def test_duplicate_properties_or_scope_are_rejected(self):
        for name, suffix in (("module.prop", b"autoHotReload=true\n"), ("scope.list", b"\nsystem\n")):
            with self.subTest(name=name):
                resources = metadata()
                resources[name] += suffix
                with self.assertRaises(ValueError):
                    validate_metadata(resources)

    def test_legacy_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "legacy assets"):
                validate_apk(self.apk(directory, extra={"assets/xposed_init": b"old.Module"}))

    def test_legacy_manifest_metadata_is_rejected_in_both_axml_encodings(self):
        for encoding in ("utf-8", "utf-16le"):
            with self.subTest(encoding=encoding), tempfile.TemporaryDirectory() as directory:
                manifest = b"\x03\0\x08\0" + "xposedmodule\0".encode(encoding)
                with self.assertRaisesRegex(ValueError, "legacy module metadata"):
                    validate_apk(self.apk(directory, manifest=manifest))

    def test_legacy_plain_xml_metadata_is_rejected(self):
        manifest = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><meta-data android:name="xposedmodule" android:value="true"/></application></manifest>'
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "legacy module metadata"):
                validate_apk(self.apk(directory, manifest=manifest))

    def test_packaged_framework_and_compat_definitions_in_secondary_dex_are_rejected(self):
        for descriptor in (API_DESCRIPTOR, "Lde/robv/android/xposed/XposedBridge;",
                           "Lcom/system/location/service/hook/compat/Adapter;"):
            with self.subTest(descriptor=descriptor), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(ValueError, "classes are packaged"):
                    validate_apk(self.apk(directory, extra={"classes2.dex": dex([descriptor])}))

    def test_reference_to_entry_cannot_replace_entry_definition(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "class definition is missing"):
                validate_apk(self.apk(directory, classes=dex(["Lexample/Other;"], [ENTRY_DESCRIPTOR])))

    def test_malformed_dex_tables_fail_closed(self):
        truncated = bytearray(dex([ENTRY_DESCRIPTOR]))
        struct.pack_into("<I", truncated, 96, 0xFFFFFFFF)
        bad_type = bytearray(dex([ENTRY_DESCRIPTOR]))
        class_offset = struct.unpack_from("<I", bad_type, 100)[0]
        struct.pack_into("<I", bad_type, class_offset, 0xFFFFFFFF)
        for data in (b"not dex", bytes(truncated), bytes(bad_type)):
            with self.subTest(data=data[:8]), self.assertRaises(ValueError):
                dex_class_descriptors(data)

    def source(self, directory):
        root = Path(directory)
        for name, data in metadata().items():
            path = root / RESOURCE_DIR / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        return root

    def test_source_guard_accepts_native_api_and_rejects_legacy_callbacks(self):
        for content, valid in (("import io.github.libxposed.api.XposedInterface", True),
                               ("import de.robv.android.xposed.XC_MethodHook", False),
                               ("import com.system.location.service.hook.compat.Adapter", False),
                               ("class XC_MethodHook", False)):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as directory:
                root = self.source(directory)
                path = root / "xposed/src/main/java/Hook.kt"
                path.parent.mkdir(parents=True)
                path.write_text(content, encoding="utf-8")
                self.assertEqual(valid, not source_violations(root))

    def test_source_guard_rejects_legacy_dependency_and_packaged_api(self):
        for content, valid in (('compileOnly(libs.libxposed.api)', True),
                               ('implementation(libs.libxposed.api)', False),
                               ('api(libs.libxposed.api)', False),
                               ('implementation("io.github.libxposed:api:102.0.0")', False),
                               ('compileOnly("de.robv.android.xposed:api:82")', False)):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as directory:
                root = self.source(directory)
                path = root / "xposed/build.gradle.kts"
                path.write_text(content, encoding="utf-8")
                self.assertEqual(valid, not source_violations(root))

    def test_source_guard_rejects_legacy_manifest_and_entry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.source(directory)
            manifest = root / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application><meta-data android:name="xposedminversion"/></application></manifest>', encoding="utf-8")
            entry = root / "xposed/src/main/assets/xposed_init"
            entry.parent.mkdir(parents=True)
            entry.write_text("old.Module", encoding="utf-8")
            violations = source_violations(root)
            self.assertTrue(any("legacy module metadata" in error for error in violations))
            self.assertTrue(any("legacy entry remains" in error for error in violations))


if __name__ == "__main__":
    unittest.main()
