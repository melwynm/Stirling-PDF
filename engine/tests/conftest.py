from __future__ import annotations

import os

from pytest import Config


def pytest_configure(config: Config) -> None:
    # Set required env vars in case there is no .env file
    os.environ.setdefault("STIRLING_OPENAI_API_KEY", "test")
    os.environ.setdefault("STIRLING_OPENAI_BASE_URL", "")
    os.environ.setdefault("STIRLING_ANTHROPIC_API_KEY", "")
    os.environ.setdefault("STIRLING_SMART_MODEL", "gpt-5-mini")
    os.environ.setdefault("STIRLING_FAST_MODEL", "gpt-5-mini")
    os.environ.setdefault("STIRLING_SMART_MODEL_REASONING_EFFORT", "medium")
    os.environ.setdefault("STIRLING_FAST_MODEL_REASONING_EFFORT", "minimal")
    os.environ.setdefault("STIRLING_SMART_MODEL_TEXT_VERBOSITY", "medium")
    os.environ.setdefault("STIRLING_FAST_MODEL_TEXT_VERBOSITY", "low")
    os.environ.setdefault("STIRLING_AI_MAX_TOKENS", "")
    os.environ.setdefault("STIRLING_SMART_MODEL_MAX_TOKENS", "8192")
    os.environ.setdefault("STIRLING_FAST_MODEL_MAX_TOKENS", "2048")
    os.environ.setdefault("STIRLING_CLAUDE_MAX_TOKENS", "4096")
    os.environ.setdefault("STIRLING_DEFAULT_MODEL_MAX_TOKENS", "4096")
    os.environ.setdefault("STIRLING_POSTHOG_API_KEY", "test")
    os.environ.setdefault("STIRLING_POSTHOG_HOST", "https://example.invalid")
    os.environ.setdefault("STIRLING_JAVA_BACKEND_URL", "http://localhost:8080")
    os.environ.setdefault("STIRLING_JAVA_BACKEND_API_KEY", "test")
    os.environ.setdefault("STIRLING_JAVA_REQUEST_TIMEOUT_SECONDS", "30")
    os.environ.setdefault("STIRLING_AI_RAW_DEBUG", "0")
    os.environ.setdefault("STIRLING_FLASK_DEBUG", "0")
    os.environ.setdefault("STIRLING_LOG_PATH", "")
    os.environ.setdefault("STIRLING_PDF_EDITOR_TABLE_DEBUG", "0")
    os.environ.setdefault("STIRLING_PDF_TAURI_MODE", "false")
    os.environ.setdefault("STIRLING_AI_STREAMING", "true")
    os.environ.setdefault("STIRLING_AI_PREVIEW_MAX_INFLIGHT", "3")
    os.environ.setdefault("STIRLING_AI_REQUEST_TIMEOUT", "70")
