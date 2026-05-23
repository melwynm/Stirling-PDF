from __future__ import annotations

from pathlib import Path

from scripts.mcp_windows_setup import build_client_config, check_environment, write_config_files


def test_build_client_config_uses_launcher_and_backend_url():
    config = build_client_config(
        backend_url="http://localhost:8081",
        allowed_roots=[Path("C:/Github/Stirling-PDF"), Path("C:/Users/example/Documents")],
        include_openai_placeholder=False,
    )

    server = config["mcpServers"]["stirling-pdf"]
    assert server["command"] == "uv"
    assert server["args"][-1] == "scripts/mcp_launcher.py"
    assert server["env"]["STIRLING_JAVA_BACKEND_URL"] == "http://localhost:8081"
    assert "STIRLING_OPENAI_API_KEY" not in server["env"]


def test_write_config_files_creates_client_snippets(tmp_path: Path):
    config = build_client_config(
        backend_url="http://localhost:8081",
        allowed_roots=[Path("C:/Github/Stirling-PDF")],
        include_openai_placeholder=True,
    )

    paths = write_config_files(config, tmp_path)

    assert {path.name for path in paths} == {
        "claude_desktop_config.stirling-pdf.json",
        "cursor-mcp.stirling-pdf.json",
    }
    assert all(path.exists() for path in paths)
    assert "replace-me" in paths[0].read_text(encoding="utf-8")


def test_check_environment_reports_missing_backend_url():
    checks = check_environment("")
    by_name = {check["name"]: check for check in checks}

    assert by_name["backend-url"]["status"] == "fail"
