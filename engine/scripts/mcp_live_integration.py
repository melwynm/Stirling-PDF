from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

ENGINE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ENGINE_ROOT.parent
sys.path.insert(0, str(ENGINE_ROOT / "src"))

from mcp_support import StirlingMcpToolRegistry  # noqa: E402


@dataclass(frozen=True)
class LiveCase:
    name: str
    tool_name: str
    arguments: dict[str, Any]
    optional: bool = False


def call(registry: StirlingMcpToolRegistry, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    payload = registry.call_tool(name, arguments)
    content = payload["content"]
    if not isinstance(content, list) or not content:
        raise RuntimeError(f"{name} returned no content.")
    first = content[0]
    if not isinstance(first, dict) or not isinstance(first.get("text"), str):
        raise RuntimeError(f"{name} returned unexpected content: {payload}")
    text = str(first["text"])
    parsed = json.loads(text)
    if not isinstance(parsed, dict):
        raise RuntimeError(f"{name} returned non-object JSON.")
    return parsed


def require_saved_output(result: dict[str, Any]) -> str:
    saved_path = result.get("savedPath")
    if not isinstance(saved_path, str):
        raise RuntimeError(f"Result did not include savedPath: {result}")
    path = Path(saved_path)
    if not path.exists():
        raise RuntimeError(f"Output path does not exist: {path}")
    if path.stat().st_size <= 0:
        raise RuntimeError(f"Output path is empty: {path}")
    return str(path)


def live_cases(fixture: Path, output_dir: Path) -> list[LiveCase]:
    return [
        LiveCase(
            "rotate",
            "stirling_rotate_pdf",
            {"pdf_path": str(fixture), "angle": 90, "output_path": str(output_dir / "rotated.pdf")},
        ),
        LiveCase(
            "merge",
            "stirling_merge_pdfs",
            {"pdf_paths": [str(fixture), str(fixture)], "output_path": str(output_dir / "merged.pdf")},
        ),
        LiveCase(
            "compress",
            "stirling_compress_pdf",
            {
                "pdf_path": str(fixture),
                "compression_method": "quality",
                "compression_level": 3,
                "output_path": str(output_dir / "compressed.pdf"),
            },
        ),
        LiveCase(
            "remove-pages",
            "stirling_remove_pages",
            {"pdf_path": str(fixture), "page_numbers": "1", "output_path": str(output_dir / "removed.pdf")},
        ),
        LiveCase(
            "extract-pages",
            "stirling_extract_pages",
            {"pdf_path": str(fixture), "page_numbers": "1", "output_path": str(output_dir / "extracted.pdf")},
        ),
        LiveCase(
            "edit-table-of-contents",
            "stirling_edit_table_of_contents",
            {
                "pdf_path": str(fixture),
                "bookmarks": [{"title": "Live Contract", "page_number": 1}],
                "output_path": str(output_dir / "toc.pdf"),
            },
        ),
        LiveCase(
            "add-attachments",
            "stirling_add_attachments",
            {
                "pdf_path": str(fixture),
                "attachment_paths": [str(fixture)],
                "output_path": str(output_dir / "attachments.pdf"),
            },
        ),
        LiveCase(
            "replace-colors",
            "stirling_replace_colors",
            {
                "pdf_path": str(fixture),
                "replace_and_invert_option": "HIGH_CONTRAST_COLOR",
                "output_path": str(output_dir / "colors.pdf"),
            },
            optional=True,
        ),
        LiveCase(
            "change-metadata",
            "stirling_change_metadata",
            {"pdf_path": str(fixture), "title": "MCP Live Contract", "output_path": str(output_dir / "metadata.pdf")},
        ),
        LiveCase(
            "remove-blank-pages",
            "stirling_remove_blank_pages",
            {"pdf_path": str(fixture), "output_path": str(output_dir / "remove-blanks.zip")},
            optional=True,
        ),
        LiveCase(
            "split-scanned-photos",
            "stirling_split_scanned_photos",
            {"pdf_path": str(fixture), "output_path": str(output_dir / "scanned-photos.zip")},
            optional=True,
        ),
    ]


def main() -> int:
    if not os.environ.get("STIRLING_JAVA_BACKEND_URL"):
        print("Set STIRLING_JAVA_BACKEND_URL before running live MCP integration tests.", file=sys.stderr)
        return 2

    fixture = REPO_ROOT / "testing" / "test_pdf_1.pdf"
    if not fixture.exists():
        print(f"Fixture PDF not found: {fixture}", file=sys.stderr)
        return 2

    include_optional = os.environ.get("STIRLING_MCP_LIVE_INCLUDE_OPTIONAL") == "true"
    output_dir = ENGINE_ROOT / "output" / "mcp-live"
    output_dir.mkdir(parents=True, exist_ok=True)

    registry = StirlingMcpToolRegistry()
    failures: list[str] = []
    skipped: list[str] = []
    passed: list[str] = []
    for case in live_cases(fixture, output_dir):
        if case.optional and not include_optional:
            skipped.append(case.name)
            print(f"SKIP {case.name}: optional dependency-heavy case")
            continue
        try:
            result = call(registry, case.tool_name, case.arguments)
            saved_path = require_saved_output(result)
            passed.append(case.name)
            print(f"PASS {case.name}: {saved_path}")
        except Exception as exc:
            failures.append(f"{case.name}: {exc}")

    print(f"SUMMARY passed={len(passed)} skipped={len(skipped)} failed={len(failures)}")
    if failures:
        print("\n".join(f"FAIL {item}" for item in failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
