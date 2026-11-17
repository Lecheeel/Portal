import copy
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from check_release_optimization import (
    CHAIN, ENTRY, HOOKER, JNI, PREFIX, dex_definitions, validate_apk, validate_callbacks,
)

OWNER = PREFIX + "hook/TestHook;"
INTERCEPT = ("intercept", f"({CHAIN})Ljava/lang/Object;")


def hook_dex(defined=True):
    """Minimal DEX tables; intercept is always referenced, optionally defined."""
    types = [OWNER, HOOKER, CHAIN, "Ljava/lang/Object;"]
    strings = types + ["intercept"]
    result = bytearray(112 + len(strings) * 4 + len(types) * 4 + 12 + 8 + 32)
    result[:8] = b"dex\n039\0"
    string_off = 112
    type_off = string_off + len(strings) * 4
    proto_off = type_off + len(types) * 4
    method_off = proto_off + 12
    class_off = method_off + 8
    for header, count, offset in ((56, len(strings), string_off), (64, len(types), type_off),
                                  (72, 1, proto_off), (88, 1, method_off), (96, 1, class_off)):
        struct.pack_into("<II", result, header, count, offset)
    for i, value in enumerate(strings):
        struct.pack_into("<I", result, string_off + i * 4, len(result))
        result.extend(bytes([len(value)]) + value.encode() + b"\0")
    for i in range(len(types)):
        struct.pack_into("<I", result, type_off + i * 4, i)
    params_off = len(result)
    result.extend(struct.pack("<IH", 1, 2))
    interface_off = len(result)
    result.extend(struct.pack("<IH", 1, 1))
    data_off = len(result)
    # No fields or direct methods, one public virtual method with a code offset.
    result.extend(bytes([0, 0, 0, int(defined)]))
    if defined:
        result.extend(bytes([0, 1, 1]))
    struct.pack_into("<III", result, proto_off, 0, 3, params_off)
    struct.pack_into("<HHI", result, method_off, 0, 0, 4)
    struct.pack_into("<I", result, class_off, 0)
    struct.pack_into("<I", result, class_off + 12, interface_off)
    struct.pack_into("<I", result, class_off + 24, data_off)
    return bytes(result)


def callbacks():
    classes = dex_definitions(hook_dex())
    classes[ENTRY] = ([], {(name, "()V"): (1, 1) for name in
                          ("onModuleLoaded", "onSystemServerStarting", "onPackageReady")})
    for owner, methods in JNI.items():
        classes[owner] = ([], {method: (0x101, 0) for method in methods})
    return classes


class ReleaseOptimizationTest(unittest.TestCase):
    def test_definition_is_distinct_from_method_reference(self):
        self.assertIn(INTERCEPT, dex_definitions(hook_dex())[OWNER][1])
        self.assertNotIn(INTERCEPT, dex_definitions(hook_dex(False))[OWNER][1])
        classes = callbacks()
        classes.update(dex_definitions(hook_dex(False)))
        with self.assertRaisesRegex(ValueError, "intercept implementation missing"):
            validate_callbacks(classes)

    def test_callbacks_survive(self):
        validate_callbacks(callbacks())

    def test_missing_lifecycle_or_native_definition_is_rejected(self):
        original = callbacks()
        for owner in (ENTRY, *JNI):
            for method in original[owner][1]:
                with self.subTest(owner=owner, method=method):
                    classes = copy.deepcopy(original)
                    del classes[owner][1][method]
                    with self.assertRaises(ValueError):
                        validate_callbacks(classes)

    def test_renamed_abstract_or_private_intercept_is_rejected(self):
        for method, flags, code in ((('a', INTERCEPT[1]), 1, 1),
                                     (INTERCEPT, 0x401, 0), (INTERCEPT, 2, 1)):
            with self.subTest(method=method, flags=flags):
                classes = callbacks()
                classes[OWNER] = ([HOOKER], {method: (flags, code)})
                with self.assertRaisesRegex(ValueError, "intercept implementation missing"):
                    validate_callbacks(classes)

    def test_repackaged_hooker_is_rejected(self):
        classes = callbacks()
        classes['La/b;'] = classes.pop(OWNER)
        with self.assertRaisesRegex(ValueError, "stack-cleaning prefix"):
            validate_callbacks(classes)

    def test_uncompressed_secondary_dex_is_rejected(self):
        for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            with self.subTest(compression=compression), tempfile.TemporaryDirectory() as directory:
                apk = Path(directory) / 'portal.apk'
                with zipfile.ZipFile(apk, 'w') as archive:
                    archive.writestr('classes.dex', hook_dex(), compress_type=zipfile.ZIP_DEFLATED)
                    archive.writestr('classes2.dex', hook_dex(), compress_type=compression)
                # Callback validation is tested above; isolate APK compression handling.
                with patch('check_release_optimization.validate_callbacks'):
                    if compression == zipfile.ZIP_STORED:
                        with self.assertRaisesRegex(ValueError, "not compressed: classes2.dex"):
                            validate_apk(apk)
                    else:
                        validate_apk(apk)

    def test_truncated_dex_is_rejected(self):
        with self.assertRaises(ValueError):
            dex_definitions(hook_dex()[:115])


if __name__ == '__main__':
    unittest.main()
