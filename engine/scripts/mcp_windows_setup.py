from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any

ENGINE_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ENGINE_ROOT.parent
DEFAULT_BACKEND_URL = "http://localhost:8081"
OUTPUT_DIR = ENGINE_ROOT / "output" / "mcp-client-config"


def build_client_config(
    *,
    backend_url: str,
    allowed_roots: list[Path],
    include_openai_placeholder: bool,
) -> dict[str, Any]:
    env: dict[str, str] = {
        "UV_CACHE_DIR": str(ENGINE_ROOT / ".uv-cache"),
        "STIRLING_JAVA_BACKEND_URL": backend_url,
        "STIRLING_MCP_ALLOWED_ROOTS": os.pathsep.join(str(path) for path in allowed_roots),
    }
    if include_openai_placeholder:
        env["STIRLING_OPENAI_API_KEY"] = "replace-me"
    return {
        "mcpServers": {
            "stirling-pdf": {
                "command": "uv",
                "args": ["--directory", str(ENGINE_ROOT), "run", "python", "scripts/mcp_launcher.py"],
                "cwd": str(ENGINE_ROOT),
                "env": env,
            }
        }
    }


def check_environment(backend_url: str) -> list[dict[str, str]]:
    checks = [
        _check("windows", os.name == "nt", "Windows host detected.", "This helper is intended for Windows."),
        _check("uv", shutil.which("uv") is not None, "uv is on PATH.", "Install uv and ensure it is on PATH."),
        _check(
            "launcher",
            (ENGINE_ROOT / "scripts" / "mcp_launcher.py").exists(),
            "MCP launcher exists.",
            "scripts/mcp_launcher.py is missing.",
        ),
        _check(
            "backend-url",
            bool(backend_url.strip()),
            f"Backend URL set to {backend_url}.",
            "Provide --backend-url, for example http://localhost:8081.",
        ),
    ]
    if shutil.which("uv") is not None:
        checks.append(_uv_version_check())
    return checks


def _check(name: str, ok: bool, pass_message: str, fail_message: str) -> dict[str, str]:
    return {"name": name, "status": "pass" if ok else "fail", "message": pass_message if ok else fail_message}


def _uv_version_check() -> dict[str, str]:
    try:
        completed = subprocess.run(
            ["uv", "--version"],
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        return {"name": "uv-version", "status": "fail", "message": f"Unable to run uv --version: {exc}"}
    output = (completed.stdout or completed.stderr).strip()
    if completed.returncode != 0:
        return {"name": "uv-version", "status": "fail", "message": output or "uv --version failed."}
    return {"name": "uv-version", "status": "pass", "message": output}


def write_config_files(config: dict[str, Any], output_dir: Path = OUTPUT_DIR) -> list[Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    paths = [
        output_dir / "claude_desktop_config.stirling-pdf.json",
        output_dir / "cursor-mcp.stirling-pdf.json",
    ]
    payload = json.dumps(config, indent=2, ensure_ascii=True) + "\n"
    for path in paths:
        path.write_text(payload, encoding="utf-8")
    return paths


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check and generate Windows MCP client config snippets.")
    parser.add_argument("--backend-url", default=DEFAULT_BACKEND_URL)
    parser.add_argument(
        "--allowed-root",
        action="append",
        default=[],
        help="Allowed filesystem root. Repeatable. Defaults to repo root and current user's Documents.",
    )
    parser.add_argument("--no-openai-placeholder", action="store_true")
    parser.add_argument("--check-only", action="store_true", help="Run checks without writing config snippets.")
    return parser.parse_args()


def default_allowed_roots() -> list[Path]:
    roots = [REPO_ROOT]
    documents = Path.home() / "Documents"
    if documents.exists():
        roots.append(documents)
    return roots


def main() -> int:
    args = parse_args()
    allowed_roots = [Path(item).expanduser().resolve() for item in args.allowed_root] or default_allowed_roots()
    checks = check_environment(args.backend_url)
    for check in checks:
        print(f"{check['status'].upper()} {check['name']}: {check['message']}")

    failed = [check for check in checks if check["status"] == "fail"]
    if failed:
        return 1

    config = build_client_config(
        backend_url=args.backend_url,
        allowed_roots=allowed_roots,
        include_openai_placeholder=not args.no_openai_placeholder,
    )
    if args.check_only:
        print(json.dumps(config, indent=2, ensure_ascii=True))
        return 0

    written = write_config_files(config)
    for path in written:
        print(f"WROTE {path}")
    print("Review these snippets and paste the stirling-pdf server block into your MCP client config.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
