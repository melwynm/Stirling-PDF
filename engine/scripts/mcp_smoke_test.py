from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

ENGINE_ROOT = Path(__file__).resolve().parents[1]
SERVER = ENGINE_ROOT / "src" / "mcp_server.py"
LAUNCHER = ENGINE_ROOT / "scripts" / "mcp_launcher.py"


def _request(process: subprocess.Popen[str], request_id: int, method: str, params: dict[str, Any]) -> dict[str, Any]:
    if process.stdin is None or process.stdout is None:
        raise RuntimeError("MCP server stdio is not available.")
    process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}) + "\n")
    process.stdin.flush()
    raw = process.stdout.readline()
    if not raw:
        stderr = process.stderr.read() if process.stderr is not None else ""
        raise RuntimeError(f"MCP server exited before responding to {method}: {stderr}")
    payload = json.loads(raw)
    if "error" in payload:
        raise RuntimeError(f"{method} failed: {payload['error']}")
    result = payload.get("result")
    if not isinstance(result, dict):
        raise RuntimeError(f"{method} returned an unexpected result: {payload}")
    return result


def _tool_json(result: dict[str, Any]) -> dict[str, Any]:
    content = result.get("content")
    if not isinstance(content, list) or not content or not isinstance(content[0], dict):
        raise RuntimeError(f"Tool returned an unexpected content payload: {result}")
    text = content[0].get("text")
    if not isinstance(text, str):
        raise RuntimeError(f"Tool returned non-text content: {result}")
    parsed = json.loads(text)
    if not isinstance(parsed, dict):
        raise RuntimeError(f"Tool returned non-object JSON: {text}")
    return parsed


def main() -> int:
    env = os.environ.copy()
    env["PYTHONUNBUFFERED"] = "1"
    if not env.get("STIRLING_JAVA_BACKEND_URL"):
        print("Set STIRLING_JAVA_BACKEND_URL before running the MCP smoke test.", file=sys.stderr)
        return 2

    process = subprocess.Popen(
        [sys.executable, str(LAUNCHER)],
        cwd=Path.cwd(),
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    try:
        initialize = _request(
            process,
            1,
            "initialize",
            {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "stirling-mcp-smoke-test", "version": "1"},
            },
        )
        server_name = initialize.get("serverInfo", {}).get("name")
        if server_name != "stirling-pdf-engine-mcp":
            raise RuntimeError(f"Unexpected MCP server info: {initialize}")
        print(f"PASS initialize: {server_name}")

        tools = _request(process, 2, "tools/list", {})
        tool_names = {item.get("name") for item in tools.get("tools", []) if isinstance(item, dict)}
        required = {"stirling_health_check", "stirling_rotate_pdf", "stirling_discover_pdfs"}
        if missing := sorted(required - tool_names):
            raise RuntimeError(f"tools/list is missing {missing}")
        print(f"PASS tools/list: {len(tool_names)} tools")

        health_result = _request(
            process,
            3,
            "tools/call",
            {
                "name": "stirling_health_check",
                "arguments": {"run_backend_operation_probe": True, "run_ai_provider_probe": False},
            },
        )
        health = _tool_json(health_result)
        checks = {
            item.get("name"): item
            for item in health.get("checks", [])
            if isinstance(item, dict) and isinstance(item.get("name"), str)
        }
        operation_probe = checks.get("backend.operationProbe", {})
        if operation_probe.get("status") != "pass":
            raise RuntimeError(f"rotate probe did not pass: {operation_probe}")
        print(f"PASS stirling_health_check: status={health.get('status')} rotate-probe=pass")
        return 0
    except Exception as exc:
        print(f"FAIL {exc}", file=sys.stderr)
        return 1
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()


if __name__ == "__main__":
    raise SystemExit(main())
