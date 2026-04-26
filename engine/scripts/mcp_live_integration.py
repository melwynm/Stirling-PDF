from __future__ import annotations

import json
import os
import sys
from pathlib import Path

ENGINE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ENGINE_ROOT.parent
sys.path.insert(0, str(ENGINE_ROOT / "src"))

from mcp_support import StirlingMcpToolRegistry  # noqa: E402


def call(registry: StirlingMcpToolRegistry, name: str, arguments: dict[str, object]) -> dict[str, object]:
    payload = registry.call_tool(name, arguments)
    text = payload["content"][0]["text"]  # type: ignore[index]
    return json.loads(str(text))


def main() -> int:
    if not os.environ.get("STIRLING_JAVA_BACKEND_URL"):
        print("Set STIRLING_JAVA_BACKEND_URL before running live MCP integration tests.", file=sys.stderr)
        return 2

    fixture = REPO_ROOT / "testing" / "test_pdf_1.pdf"
    if not fixture.exists():
        print(f"Fixture PDF not found: {fixture}", file=sys.stderr)
        return 2

    output_dir = ENGINE_ROOT / "output" / "mcp-live"
    output_dir.mkdir(parents=True, exist_ok=True)

    registry = StirlingMcpToolRegistry()
    checks = [
        (
            "stirling_rotate_pdf",
            {"pdf_path": str(fixture), "angle": 90, "output_path": str(output_dir / "rotated.pdf")},
        ),
        (
            "stirling_merge_pdfs",
            {
                "pdf_paths": [str(fixture), str(fixture)],
                "output_path": str(output_dir / "merged.pdf"),
            },
        ),
        (
            "stirling_compress_pdf",
            {
                "pdf_path": str(fixture),
                "compression_method": "quality",
                "compression_level": 3,
                "output_path": str(output_dir / "compressed.pdf"),
            },
        ),
        (
            "stirling_remove_pages",
            {"pdf_path": str(fixture), "page_numbers": "1", "output_path": str(output_dir / "removed.pdf")},
        ),
    ]

    failures: list[str] = []
    for name, args in checks:
        try:
            result = call(registry, name, args)
            saved_path = result.get("savedPath")
            if not isinstance(saved_path, str) or not Path(saved_path).exists():
                failures.append(f"{name}: no savedPath result: {result}")
            else:
                print(f"PASS {name}: {saved_path}")
        except Exception as exc:
            failures.append(f"{name}: {exc}")

    if failures:
        print("\n".join(f"FAIL {item}" for item in failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
