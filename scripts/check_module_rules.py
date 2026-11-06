#!/usr/bin/env python3
"""Repository guardrails for the long-term roadmap (P0-05).

The checks are intentionally small and dependency-free so they run in the existing
`python3 -m unittest discover -s scripts` PR job:

* `:core` / `:nmea` stay pure JVM (no Android imports);
* `:xposed` never depends on `:app`, `:app` never depends on `:detector`;
* `:detector` imports nothing from the implementation it evaluates;
* hook files respect the 400-line budget, with a checked-in baseline for legacy files;
* every relative Markdown link under `docs/roadmap/` resolves.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINE_BUDGET = 400
BASELINE_PATH = Path("docs/roadmap/line-budget-baseline.json")
HOOK_DIR = Path("xposed/src/main/java/com/system/location/service/hook")
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([A-Za-z_][\w.]*)", re.MULTILINE)
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
PROJECT_DEP_RE = re.compile(r"project\s*\(\s*[\"'](:[A-Za-z0-9_.-]+)[\"']\s*\)")


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def _imports(path: Path) -> list[str]:
    return IMPORT_RE.findall(_read(path))


def check_pure_jvm(root: Path = ROOT) -> list[str]:
    problems: list[str] = []
    for module in ("core", "nmea"):
        source_root = root / module / "src" / "main"
        if not source_root.exists():
            continue
        for path in source_root.rglob("*"):
            if path.suffix not in {".kt", ".java"}:
                continue
            for imported in _imports(path):
                if imported.startswith(("android.", "androidx.")):
                    problems.append(f"{path.relative_to(root).as_posix()}: pure JVM module imports {imported}")
    return problems


def check_forbidden_dependency(root: Path, build_rel: str, forbidden: str) -> list[str]:
    build = root / build_rel
    if not build.exists():
        return []
    dependencies = PROJECT_DEP_RE.findall(_read(build))
    if forbidden in dependencies:
        return [f"{build_rel}: forbidden project dependency {forbidden}"]
    return []


def check_detector_isolation(root: Path = ROOT) -> list[str]:
    problems: list[str] = []
    build_rel = "detector/build.gradle.kts"
    build = root / build_rel
    if build.exists():
        dependencies = PROJECT_DEP_RE.findall(_read(build))
        if dependencies:
            problems.append(f"{build_rel}: detector must not depend on project modules: {', '.join(dependencies)}")
    source_root = root / "detector" / "src"
    if source_root.exists():
        for path in source_root.rglob("*.kt"):
            for imported in _imports(path):
                if imported.startswith("com.system.location.service.") and not imported.startswith(
                    "com.system.location.service.detector"
                ):
                    problems.append(
                        f"{path.relative_to(root).as_posix()}: detector imports implementation class {imported}"
                    )
    return problems


def check_line_budget(root: Path = ROOT) -> list[str]:
    baseline: dict[str, int] = {}
    baseline_file = root / BASELINE_PATH
    if baseline_file.exists():
        baseline = json.loads(_read(baseline_file))
    problems: list[str] = []
    hook_root = root / HOOK_DIR
    if not hook_root.exists():
        return problems
    for path in hook_root.rglob("*.kt"):
        rel = path.relative_to(root).as_posix()
        current = len(_read(path).splitlines())
        allowed = int(baseline.get(rel, LINE_BUDGET))
        if current > allowed:
            suffix = " (baseline)" if rel in baseline else " (new file)"
            problems.append(f"{rel}: {current} lines > budget {allowed}{suffix}")
    return problems


def check_roadmap_links(root: Path = ROOT) -> list[str]:
    problems: list[str] = []
    doc_root = root / "docs" / "roadmap"
    if not doc_root.exists():
        return [f"docs/roadmap is missing"]
    for doc in doc_root.rglob("*.md"):
        for link in LINK_RE.findall(_read(doc)):
            if link.startswith(("http://", "https://", "#", "mailto:")):
                continue
            target = link.split("#", 1)[0].strip()
            if not target:
                continue
            if not (doc.parent / target).exists():
                problems.append(f"{doc.relative_to(root).as_posix()} -> {link} (missing)")
    return problems


def violations(root: Path = ROOT) -> list[str]:
    return [
        *check_pure_jvm(root),
        *check_forbidden_dependency(root, "xposed/build.gradle.kts", ":app"),
        *check_forbidden_dependency(root, "app/build.gradle.kts", ":detector"),
        *check_detector_isolation(root),
        *check_line_budget(root),
        *check_roadmap_links(root),
    ]


def main() -> int:
    problems = violations()
    for problem in problems:
        print(f"FAIL {problem}")
    print(f"module rules: {len(problems)} violation(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
