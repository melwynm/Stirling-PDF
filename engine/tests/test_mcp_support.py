from __future__ import annotations

import json
import os
from io import BytesIO
from pathlib import Path
from typing import Any

from pytest import MonkeyPatch

import models
from mcp_support import (
    FrontendOperationMetadataResolver,
    MultipartEndpointExecutor,
    StirlingMcpToolRegistry,
)

_REPO_ROOT = Path(__file__).resolve().parents[2]
_FIXTURE_PDF = _REPO_ROOT / "testing" / "test_pdf_1.pdf"


class _FakeHttpResponse:
    def __init__(self, body: bytes, headers: dict[str, str], status: int = 200) -> None:
        self._body = body
        self._offset = 0
        self.headers = headers
        self.status = status

    def read(self, size: int = -1) -> bytes:
        if size is None or size < 0:
            size = len(self._body) - self._offset
        start = self._offset
        end = min(start + size, len(self._body))
        self._offset = end
        return self._body[start:end]

    def __enter__(self) -> _FakeHttpResponse:
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> bool:
        return False


class _FakeOutputParent:
    def mkdir(self, *, parents: bool = False, exist_ok: bool = False) -> None:
        return None


class _FakeOutputPath:
    def __init__(self, display_path: str) -> None:
        self.display_path = display_path
        self.parent = _FakeOutputParent()
        self.written_bytes: bytes | None = None

    def write_bytes(self, data: bytes) -> int:
        self.written_bytes = data
        return len(data)

    def open(self, mode: str):
        assert mode == "wb"
        path = self

        class _Writer:
            def __enter__(self) -> Any:
                path.written_bytes = b""
                return self

            def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> bool:
                return False

            def write(self, data: bytes) -> int:
                path.written_bytes = (path.written_bytes or b"") + data
                return len(data)

        return _Writer()

    def __str__(self) -> str:
        return self.display_path


class _FakeBodyPath:
    def __init__(self, body: bytes = b"multipart-body") -> None:
        self.body = body
        self.unlinked = False

    def open(self, mode: str):
        assert mode == "rb"
        return BytesIO(self.body)

    def unlink(self, *, missing_ok: bool = False) -> None:
        self.unlinked = True


def test_frontend_metadata_resolver_finds_rotate():
    resolver = FrontendOperationMetadataResolver()
    metadata = resolver.get("rotate")

    assert metadata is not None
    assert metadata.operation_id == "rotate"
    assert metadata.endpoint_expression == "'/api/v1/general/rotate-pdf'"
    assert metadata.build_form_data_name == "buildRotateFormData"
    assert metadata.source_file.endswith("frontend/src/core/hooks/tools/rotate/useRotateOperation.ts")


def test_list_operations_includes_frontend_metadata():
    registry = StirlingMcpToolRegistry()

    payload = registry.call_tool("stirling_list_operations", {})
    parsed = json.loads(payload["content"][0]["text"])

    rotate_entry = next(item for item in parsed["operations"] if item["operationId"] == "rotate")
    assert rotate_entry["frontendMetadata"]["endpointExpression"] == "'/api/v1/general/rotate-pdf'"


def test_health_check_reports_missing_runtime_without_crashing(monkeypatch: MonkeyPatch):
    for key in list(os.environ):
        if key.startswith("STIRLING_"):
            monkeypatch.delenv(key, raising=False)
    monkeypatch.setattr("shutil.which", lambda name: None)

    registry = StirlingMcpToolRegistry()
    payload = registry.call_tool(
        "stirling_health_check",
        {"run_backend_operation_probe": False},
    )
    parsed = json.loads(payload["content"][0]["text"])
    checks = {check["name"]: check for check in parsed["checks"]}

    assert parsed["status"] == "unhealthy"
    assert checks["mcp.process"]["status"] == "pass"
    assert checks["engine.environment"]["status"] == "fail"
    assert checks["pdf.pdftohtml"]["status"] == "fail"
    assert checks["mcp.filesystem"]["status"] == "pass"
    assert checks["backend.health"]["status"] == "fail"
    assert checks["ai.provider"]["status"] == "fail"


def test_health_check_passes_with_mocked_dependencies(monkeypatch: MonkeyPatch):
    monkeypatch.setattr("shutil.which", lambda name: "C:\\tools\\pdftohtml.exe")

    def fake_urlopen(request, timeout):
        if request.get_method() == "POST":
            return _FakeHttpResponse(
                b"%PDF-1.4\n",
                {"Content-Type": "application/pdf"},
            )
        return _FakeHttpResponse(
            b'{"status":"UP","version":"test"}',
            {
                "Content-Type": "application/json",
                "Server": "test-backend",
            },
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)

    registry = StirlingMcpToolRegistry()
    payload = registry.call_tool("stirling_health_check", {})
    parsed = json.loads(payload["content"][0]["text"])
    checks = {check["name"]: check for check in parsed["checks"]}

    assert parsed["status"] == "healthy"
    assert checks["backend.health"]["status"] == "pass"
    assert checks["backend.operationProbe"]["status"] == "pass"
    assert checks["ai.provider"]["status"] == "pass"


def test_get_operation_details_returns_schema_and_defaults():
    registry = StirlingMcpToolRegistry()

    payload = registry.call_tool("stirling_get_operation_details", {"operation_id": "rotate"})
    parsed = json.loads(payload["content"][0]["text"])

    assert parsed["operationId"] == "rotate"
    assert parsed["fieldDefaults"]["angle"] == 0
    assert "properties" in parsed["inputSchema"]


def test_plan_edit_request_uses_catalog(monkeypatch: MonkeyPatch):
    class FakeCatalog:
        def get_catalog(self):
            return type("Catalog", (), {"operation_ids": [models.tool_models.OperationId.ROTATE]})()

        def get_operation(self, operation_id):
            return models.tool_models.RotateParams if operation_id == models.tool_models.OperationId.ROTATE else None

        def select_edit_tool(self, **kwargs):
            return models.EditToolSelection(
                action="call_tool",
                operation_ids=[models.tool_models.OperationId.ROTATE],
                response_message="Rotate the PDF",
            )

        def extract_operation_parameters(self, **kwargs):
            return models.tool_models.RotateParams(angle=90)

    registry = StirlingMcpToolRegistry(tool_catalog=FakeCatalog())  # type: ignore[arg-type]

    monkeypatch.setattr("mcp_support._get_pdf_preflight", lambda file_path: models.PdfPreflight(page_count=1))
    monkeypatch.setattr(
        "mcp_support._validate_operation_chain",
        lambda operation_ids: type("Validation", (), {"is_valid": True, "error_message": None, "error_data": None})(),
    )
    monkeypatch.setattr(
        "mcp_support._assess_plan_risk",
        lambda operation_ids, preflight: {"level": "low", "reasons": [], "should_confirm": False},
    )
    monkeypatch.setattr("mcp_support._build_plan_summary", lambda operation_ids: {"steps": ["rotate"]})

    payload = registry.call_tool(
        "stirling_plan_edit_request",
        {"request": "Rotate this PDF 90 degrees", "file_paths": [str(_FIXTURE_PDF)]},
    )
    parsed = json.loads(payload["content"][0]["text"])

    assert parsed["selectionAction"] == "call_tool"
    assert parsed["operations"] == [{"operationId": "rotate", "parameters": {"angle": 90.0}}]


def test_call_endpoint_saves_binary_response(monkeypatch: MonkeyPatch):
    output_path = _REPO_ROOT / "engine" / "output" / "mcp-test-result.pdf"
    fake_destination = _FakeOutputPath(str(output_path.resolve()))
    fake_body_path = _FakeBodyPath()

    monkeypatch.setattr(
        "urllib.request.urlopen",
        lambda request, timeout: _FakeHttpResponse(
            b"%PDF-output%",
            {
                "Content-Type": "application/pdf",
                "Content-Disposition": 'attachment; filename="result.pdf"',
            },
        ),
    )

    executor = MultipartEndpointExecutor(output_dir=str(_REPO_ROOT / "engine" / "output"))
    monkeypatch.setattr(
        executor,
        "_resolve_output_path",
        lambda output_path, headers, content_type: fake_destination,
    )
    monkeypatch.setattr(
        executor,
        "_write_multipart_body",
        lambda form_fields, files: (fake_body_path, "test-boundary", len(fake_body_path.body)),
    )

    result = executor.call_endpoint(
        endpoint="/api/v1/general/rotate-pdf",
        file_paths=[str(_FIXTURE_PDF)],
        file_field_name="fileInput",
        extra_file_fields={},
        form_fields={"angle": 90},
        output_path=str(output_path),
    )

    assert result["savedPath"] == str(output_path.resolve())
    assert fake_destination.written_bytes == b"%PDF-output%"
    assert fake_body_path.unlinked is True


def test_call_endpoint_waits_for_async_job(monkeypatch: MonkeyPatch):
    fake_body_path = _FakeBodyPath()
    executor = MultipartEndpointExecutor(output_dir=str(_REPO_ROOT / "engine" / "output"))

    monkeypatch.setattr(
        executor,
        "_write_multipart_body",
        lambda form_fields, files: (fake_body_path, "test-boundary", len(fake_body_path.body)),
    )
    monkeypatch.setattr(
        "urllib.request.urlopen",
        lambda request, timeout: _FakeHttpResponse(
            b'{"jobId":"job-123"}',
            {"Content-Type": "application/json"},
        ),
    )
    monkeypatch.setattr(
        executor,
        "wait_for_job",
        lambda job_id, output_path, poll_interval_seconds, poll_timeout_seconds: {
            "jobId": job_id,
            "result": {"savedPath": output_path},
        },
    )

    result = executor.call_endpoint(
        endpoint="/api/v1/general/rotate-pdf",
        file_paths=[str(_FIXTURE_PDF)],
        file_field_name="fileInput",
        extra_file_fields={},
        form_fields={"angle": 90},
        output_path="rotated.pdf",
        async_job=True,
        wait_for_job=True,
    )

    assert result == {"jobId": "job-123", "result": {"savedPath": "rotated.pdf"}}
    assert fake_body_path.unlinked is True


def test_rotate_pdf_tool_calls_backend_executor():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]

    payload = registry.call_tool(
        "stirling_rotate_pdf",
        {
            "pdf_path": str(_FIXTURE_PDF),
            "angle": 90,
            "output_path": "rotated.pdf",
            "async_job": True,
            "wait_for_job": True,
        },
    )
    parsed = json.loads(payload["content"][0]["text"])

    assert parsed["endpoint"] == "/api/v1/general/rotate-pdf"
    assert parsed["file_paths"] == [str(_FIXTURE_PDF)]
    assert parsed["file_field_name"] == "fileInput"
    assert parsed["form_fields"] == {"angle": 90}
    assert parsed["async_job"] is True
    assert parsed["wait_for_job"] is True


def test_job_status_tool_fetches_result_when_requested():
    class FakeExecutor:
        def get_job_status(self, job_id):
            return {"jobId": job_id, "status": {"complete": False}}

        def get_job_result(self, job_id, output_path):
            return {"jobId": job_id, "savedPath": output_path}

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]

    status_payload = registry.call_tool("stirling_get_job_status", {"job_id": "job-123"})
    result_payload = registry.call_tool(
        "stirling_get_job_status",
        {"job_id": "job-123", "fetch_result": True, "output_path": "done.pdf"},
    )

    assert json.loads(status_payload["content"][0]["text"])["status"] == {"complete": False}
    assert json.loads(result_payload["content"][0]["text"])["savedPath"] == "done.pdf"
