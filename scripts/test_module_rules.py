import json
import shutil
import unittest
import uuid
from contextlib import contextmanager
from pathlib import Path

from check_module_rules import (
    LINE_BUDGET,
    ROOT,
    check_detector_isolation,
    check_forbidden_dependency,
    check_line_budget,
    check_pure_jvm,
    check_roadmap_links,
    violations,
)


@contextmanager
def temp_root():
    """Scratch tree inside the ignored .tmp directory; cleanup failure must not fail a test."""
    path = ROOT / ".tmp" / f"module-rules-{uuid.uuid4().hex}"
    path.mkdir(parents=True, exist_ok=True)
    try:
        yield path
    finally:
        shutil.rmtree(path, ignore_errors=True)


class ModuleRulesTest(unittest.TestCase):
    def test_pure_jvm_module_rejects_android_import(self):
        with temp_root() as raw:
            root = Path(raw)
            source = root / "core" / "src" / "main" / "kotlin" / "Sample.kt"
            source.parent.mkdir(parents=True)
            source.write_text("import android.location.Location\nclass Sample\n", encoding="utf-8")
            self.assertTrue(check_pure_jvm(root))

    def test_pure_jvm_module_accepts_plain_kotlin(self):
        with temp_root() as raw:
            root = Path(raw)
            source = root / "nmea" / "src" / "main" / "java" / "Sample.kt"
            source.parent.mkdir(parents=True)
            source.write_text("import kotlin.math.abs\nclass Sample\n", encoding="utf-8")
            self.assertEqual([], check_pure_jvm(root))

    def test_forbidden_dependency_detects_project_reference(self):
        with temp_root() as raw:
            root = Path(raw)
            build = root / "xposed" / "build.gradle.kts"
            build.parent.mkdir(parents=True)
            build.write_text('dependencies { implementation(project(":app")) }\n', encoding="utf-8")
            self.assertTrue(check_forbidden_dependency(root, "xposed/build.gradle.kts", ":app"))

    def test_detector_must_not_import_implementation_classes(self):
        with temp_root() as raw:
            root = Path(raw)
            source = root / "detector" / "src" / "main" / "java" / "Check.kt"
            source.parent.mkdir(parents=True)
            source.write_text("import com.system.location.service.core.location.LocationSample\n", encoding="utf-8")
            self.assertTrue(check_detector_isolation(root))

    def test_line_budget_allows_baseline_but_rejects_growth(self):
        with temp_root() as raw:
            root = Path(raw)
            baseline_file = root / "docs" / "roadmap" / "line-budget-baseline.json"
            baseline_file.parent.mkdir(parents=True)
            hook = root / "xposed/src/main/java/com/system/location/service/hook/Legacy.kt"
            hook.parent.mkdir(parents=True)
            hook.write_text("\n".join("// line" for _ in range(LINE_BUDGET + 10)) + "\n", encoding="utf-8")
            baseline_file.write_text(
                json.dumps({str(hook.relative_to(root)).replace("\\", "/"): LINE_BUDGET + 20}),
                encoding="utf-8",
            )
            self.assertEqual([], check_line_budget(root))

            hook.write_text("\n".join("// line" for _ in range(LINE_BUDGET + 30)) + "\n", encoding="utf-8")
            self.assertTrue(check_line_budget(root))

    def test_new_oversized_hook_fails_budget(self):
        with temp_root() as raw:
            root = Path(raw)
            hook = root / "xposed/src/main/java/com/system/location/service/hook/NewHook.kt"
            hook.parent.mkdir(parents=True)
            hook.write_text("\n".join("// line" for _ in range(LINE_BUDGET + 1)) + "\n", encoding="utf-8")
            self.assertTrue(check_line_budget(root))

    def test_roadmap_link_must_resolve(self):
        with temp_root() as raw:
            root = Path(raw)
            readme = root / "docs" / "roadmap" / "README.md"
            readme.parent.mkdir(parents=True)
            readme.write_text("[ok](present.md) [bad](missing.md)\n", encoding="utf-8")
            (readme.parent / "present.md").write_text("# present\n", encoding="utf-8")
            problems = check_roadmap_links(root)
            self.assertEqual(1, len(problems))
            self.assertIn("missing.md", problems[0])

    def test_current_repository_passes_all_guardrails(self):
        self.assertEqual([], violations(ROOT))


if __name__ == "__main__":
    unittest.main()

