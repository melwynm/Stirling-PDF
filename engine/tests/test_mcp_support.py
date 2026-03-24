from __future__ import annotations

import json
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
    def __init__(self, body: bytes, headers: dict[str, str]) -> None:
        self._body = body
        self.headers = headers

    def read(self) -> bytes:
        return self._body

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

    def __str__(self) -> str:
        return self.display_path


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

    monkeypatch.setattr("mcp_support.get_pdf_preflight", lambda file_path: models.PdfPreflight(page_count=1))
    monkeypatch.setattr(
        "mcp_support.validate_operation_chain",
        lambda operation_ids: type("Validation", (), {"is_valid": True, "error_message": None, "error_data": None})(),
    )
    monkeypatch.setattr(
        "mcp_support.assess_plan_risk",
        lambda operation_ids, preflight: {"level": "low", "reasons": [], "should_confirm": False},
    )

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
