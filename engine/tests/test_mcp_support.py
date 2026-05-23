from __future__ import annotations

import json
import os
import urllib.error
from email.message import Message
from io import BytesIO
from pathlib import Path
from typing import Any

import pytest
from pytest import MonkeyPatch

import models
from mcp_support import (
    FrontendOperationMetadataResolver,
    MultipartEndpointExecutor,
    StirlingMcpToolRegistry,
)

_REPO_ROOT = Path(__file__).resolve().parents[2]
_FIXTURE_PDF = _REPO_ROOT / "testing" / "test_pdf_1.pdf"


def _json_payload(payload: dict[str, Any]) -> dict[str, Any]:
    content = payload["content"]
    assert isinstance(content, list)
    first = content[0]
    assert isinstance(first, dict)
    text = first["text"]
    assert isinstance(text, str)
    parsed = json.loads(text)
    assert isinstance(parsed, dict)
    return parsed


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

    def close(self) -> None:
        return None


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
        self._offset = 0
        self.closed = False

    def open(self, mode: str):
        assert mode == "rb"
        return self

    def read(self, size: int = -1) -> bytes:
        if size is None or size < 0:
            size = len(self.body) - self._offset
        start = self._offset
        end = min(start + size, len(self.body))
        self._offset = end
        return self.body[start:end]

    def close(self) -> None:
        self.closed = True


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
    parsed = _json_payload(payload)

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
    parsed = _json_payload(payload)
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
    parsed = _json_payload(payload)
    checks = {check["name"]: check for check in parsed["checks"]}

    assert parsed["status"] == "healthy"
    assert checks["backend.health"]["status"] == "pass"
    assert checks["backend.operationProbe"]["status"] == "pass"
    assert checks["ai.provider"]["status"] == "pass"


def test_get_operation_details_returns_schema_and_defaults():
    registry = StirlingMcpToolRegistry()

    payload = registry.call_tool("stirling_get_operation_details", {"operation_id": "rotate"})
    parsed = _json_payload(payload)

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
    parsed = _json_payload(payload)

    assert parsed["selectionAction"] == "call_tool"
    assert parsed["operations"] == [{"operationId": "rotate", "parameters": {"angle": 90.0}}]


def test_setup_diagnostics_includes_desktop_client_hint(monkeypatch: MonkeyPatch):
    monkeypatch.setenv("STIRLING_JAVA_BACKEND_URL", "http://localhost:8081")
    monkeypatch.delenv("UV_CACHE_DIR", raising=False)

    registry = StirlingMcpToolRegistry()
    parsed = _json_payload(registry.call_tool("stirling_setup_diagnostics", {}))

    assert parsed["clientConfigHint"]["command"] == "uv"
    assert parsed["clientConfigHint"]["args"][:2] == ["--directory", str(_REPO_ROOT / "engine")]
    assert parsed["clientConfigHint"]["args"][-1] == "scripts/mcp_launcher.py"
    assert parsed["clientConfigHint"]["env"]["STIRLING_JAVA_BACKEND_URL"] == "http://localhost:8081"
    assert any(item["code"] == "uv-cache" for item in parsed["warnings"])


def test_discover_pdfs_stays_in_allowed_roots_and_can_preflight(monkeypatch: MonkeyPatch, tmp_path: Path):
    root = tmp_path / "pdfs"
    root.mkdir()
    pdf_path = root / "chosen.pdf"
    pdf_path.write_bytes(b"%PDF-test")
    (root / "ignored.txt").write_text("not a PDF", encoding="utf-8")
    monkeypatch.setenv("STIRLING_MCP_ALLOWED_ROOTS", str(root))
    monkeypatch.setattr(
        "mcp_support._get_pdf_preflight",
        lambda file_path: models.PdfPreflight(page_count=3, has_text_layer=True),
    )

    registry = StirlingMcpToolRegistry()
    parsed = _json_payload(
        registry.call_tool(
            "stirling_discover_pdfs",
            {"root_paths": [str(root)], "include_preflight": True, "name_contains": "chosen"},
        )
    )

    assert parsed["count"] == 1
    assert parsed["pdfs"][0]["path"] == str(pdf_path.resolve())
    assert parsed["pdfs"][0]["preflight"]["pageCount"] == 3


def test_execute_plan_threads_saved_pdf_into_next_step(monkeypatch: MonkeyPatch):
    class FakeExecutor:
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []

        def call_endpoint(self, **kwargs):
            self.calls.append(kwargs)
            index = len(self.calls)
            return {"savedPath": str(_REPO_ROOT / "engine" / "output" / f"step-{index}.pdf")}

    executor = FakeExecutor()
    registry = StirlingMcpToolRegistry(endpoint_executor=executor)  # type: ignore[arg-type]
    monkeypatch.setattr(registry, "_first_pdf_preflight", lambda file_paths: models.PdfPreflight(page_count=1))
    monkeypatch.setattr("mcp_support._assess_plan_risk", lambda operation_ids, preflight: {"should_confirm": False})

    parsed = _json_payload(
        registry.call_tool(
            "stirling_execute_plan",
            {
                "file_paths": [str(_FIXTURE_PDF)],
                "operations": [
                    {"operation_id": "rotate", "parameters": {"angle": 90}},
                    {"operation_id": "flatten", "parameters": {"flattenOnlyForms": False}},
                ],
                "output_path": "final.pdf",
            },
        )
    )

    assert parsed["status"] == "completed"
    assert executor.calls[0]["file_paths"] == [str(_FIXTURE_PDF)]
    assert executor.calls[1]["file_paths"] == [str(_REPO_ROOT / "engine" / "output" / "step-1.pdf")]
    assert executor.calls[1]["output_path"] == "final.pdf"


def test_execute_plan_requires_confirmation_for_risky_chain(monkeypatch: MonkeyPatch):
    registry = StirlingMcpToolRegistry()
    monkeypatch.setattr(registry, "_first_pdf_preflight", lambda file_paths: models.PdfPreflight(page_count=1))
    monkeypatch.setattr("mcp_support._assess_plan_risk", lambda operation_ids, preflight: {"should_confirm": True})

    with pytest.raises(RuntimeError, match="requires confirmation"):
        registry.call_tool(
            "stirling_execute_plan",
            {
                "file_paths": [str(_FIXTURE_PDF)],
                "operations": [{"operation_id": "rotate", "parameters": {"angle": 90}}],
            },
        )


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
        "_open_multipart_body",
        lambda form_fields, files: (fake_body_path, "test-boundary", None),
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
    assert fake_body_path.closed is True


def test_call_endpoint_waits_for_async_job(monkeypatch: MonkeyPatch):
    fake_body_path = _FakeBodyPath()
    executor = MultipartEndpointExecutor(output_dir=str(_REPO_ROOT / "engine" / "output"))

    monkeypatch.setattr(
        executor,
        "_open_multipart_body",
        lambda form_fields, files: (fake_body_path, "test-boundary", None),
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
    assert fake_body_path.closed is True


def test_call_endpoint_formats_disabled_backend_endpoint(monkeypatch: MonkeyPatch):
    fake_body_path = _FakeBodyPath()
    executor = MultipartEndpointExecutor(output_dir=str(_REPO_ROOT / "engine" / "output"))

    monkeypatch.setattr(
        executor,
        "_open_multipart_body",
        lambda form_fields, files: (fake_body_path, "test-boundary", None),
    )

    def fake_urlopen(request, timeout):
        headers: Message[str, str] = Message()
        raise urllib.error.HTTPError(
            request.full_url,
            403,
            "Forbidden",
            headers,
            BytesIO(b'{"status":403,"error":"Forbidden","message":"This endpoint is disabled"}'),
        )

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)

    with pytest.raises(RuntimeError, match="Enable this endpoint"):
        executor.call_endpoint(
            endpoint="/api/v1/misc/replace-invert-pdf",
            file_paths=[str(_FIXTURE_PDF)],
            file_field_name="fileInput",
            extra_file_fields={},
            form_fields={},
            output_path=None,
        )
    assert fake_body_path.closed is True


def test_call_endpoint_formats_backend_connection_error(monkeypatch: MonkeyPatch):
    fake_body_path = _FakeBodyPath()
    executor = MultipartEndpointExecutor(output_dir=str(_REPO_ROOT / "engine" / "output"))

    monkeypatch.setattr(
        executor,
        "_open_multipart_body",
        lambda form_fields, files: (fake_body_path, "test-boundary", None),
    )
    monkeypatch.setattr(
        "urllib.request.urlopen",
        lambda request, timeout: (_ for _ in ()).throw(urllib.error.URLError("connection refused")),
    )

    with pytest.raises(RuntimeError, match="run stirling_health_check"):
        executor.call_endpoint(
            endpoint="/api/v1/general/rotate-pdf",
            file_paths=[str(_FIXTURE_PDF)],
            file_field_name="fileInput",
            extra_file_fields={},
            form_fields={},
            output_path=None,
        )
    assert fake_body_path.closed is True


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
    parsed = _json_payload(payload)

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

    assert _json_payload(status_payload)["status"] == {"complete": False}
    assert _json_payload(result_payload)["savedPath"] == "done.pdf"


def test_operation_adapter_tools_build_expected_backend_requests():
    class FakeExecutor:
        def __init__(self) -> None:
            self.calls: list[dict[str, Any]] = []

        def call_endpoint(self, **kwargs):
            self.calls.append(kwargs)
            return kwargs

    executor = FakeExecutor()
    registry = StirlingMcpToolRegistry(endpoint_executor=executor)  # type: ignore[arg-type]

    merge_payload = registry.call_tool(
        "stirling_merge_pdfs",
        {"pdf_paths": [str(_FIXTURE_PDF), str(_FIXTURE_PDF)], "generate_table_of_contents": True},
    )
    compress_payload = registry.call_tool(
        "stirling_compress_pdf",
        {"pdf_path": str(_FIXTURE_PDF), "compression_method": "fileSize", "expected_output_size": "10MB"},
    )
    remove_payload = registry.call_tool(
        "stirling_remove_pages",
        {"pdf_path": str(_FIXTURE_PDF), "page_numbers": "1, 3, 5-7"},
    )

    merge = _json_payload(merge_payload)
    compress = _json_payload(compress_payload)
    remove = _json_payload(remove_payload)

    assert merge["endpoint"] == "/api/v1/general/merge-pdfs"
    assert merge["form_fields"]["sortType"] == "orderProvided"
    assert merge["form_fields"]["generateToc"] is True
    assert json.loads(merge["form_fields"]["clientFileIds"]) == [f"0:{_FIXTURE_PDF.name}", f"1:{_FIXTURE_PDF.name}"]
    assert compress["endpoint"] == "/api/v1/misc/compress-pdf"
    assert compress["form_fields"]["expectedOutputSize"] == "10MB"
    assert remove["endpoint"] == "/api/v1/general/remove-pages"
    assert remove["form_fields"] == {"pageNumbers": "1,3,5-7"}


def test_executable_operations_and_more_wrappers_build_expected_requests():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]

    operations_payload = registry.call_tool("stirling_list_executable_operations", {})
    split_payload = registry.call_tool(
        "stirling_split_pdf",
        {"pdf_path": str(_FIXTURE_PDF), "page_numbers": "1, 2"},
    )
    repair_payload = registry.call_tool("stirling_repair_pdf", {"pdf_path": str(_FIXTURE_PDF)})
    sanitize_payload = registry.call_tool("stirling_sanitize_pdf", {"pdf_path": str(_FIXTURE_PDF)})

    operations = _json_payload(operations_payload)
    split = _json_payload(split_payload)
    repair = _json_payload(repair_payload)
    sanitize = _json_payload(sanitize_payload)

    assert operations["count"] >= 14
    assert operations["genericOperationCount"] >= operations["firstClassWrapperCount"]
    assert any(item["toolName"] == "stirling_split_pdf" for item in operations["operations"])
    assert split["endpoint"] == "/api/v1/general/split-pages"
    assert split["form_fields"] == {"pageNumbers": "1,2"}
    assert repair["endpoint"] == "/api/v1/misc/repair"
    assert sanitize["endpoint"] == "/api/v1/security/sanitize-pdf"


def test_operation_coverage_classifies_first_class_generic_and_client_only():
    registry = StirlingMcpToolRegistry()

    parsed = _json_payload(registry.call_tool("stirling_operation_coverage", {}))
    operations = {item["operationId"]: item for item in parsed["operations"]}

    assert parsed["count"] >= 30
    assert operations["rotate"]["coverage"] == "firstClassWrapper"
    assert operations["autoRename"]["coverage"] == "genericStaticEndpoint"
    assert operations["removeAnnotations"]["coverage"] == "clientOnly"
    assert "clientOnly" in parsed["counts"]


def test_page_edit_wrappers_build_frontend_backend_contracts():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]

    extracted = _json_payload(
        registry.call_tool("stirling_extract_pages", {"pdf_path": str(_FIXTURE_PDF), "page_numbers": "1, 3"})
    )
    crop = _json_payload(
        registry.call_tool(
            "stirling_crop_pdf",
            {"pdf_path": str(_FIXTURE_PDF), "x": 1, "y": 2, "width": 100, "height": 200},
        )
    )
    scaled = _json_payload(
        registry.call_tool(
            "stirling_scale_pages", {"pdf_path": str(_FIXTURE_PDF), "scale_factor": 0.8, "page_size": "A4"}
        )
    )
    redacted = _json_payload(
        registry.call_tool(
            "stirling_redact_pdf",
            {"pdf_path": str(_FIXTURE_PDF), "words_to_redact": ["secret", "case [0-9]+"], "confirmed": True},
        )
    )

    assert extracted["endpoint"] == "/api/v1/general/rearrange-pages"
    assert extracted["form_fields"] == {"pageNumbers": "1,3"}
    assert crop["endpoint"] == "/api/v1/general/crop"
    assert crop["form_fields"] == {"autoCrop": False, "x": 1.0, "y": 2.0, "width": 100.0, "height": 200.0}
    assert scaled["endpoint"] == "/api/v1/general/scale-pages"
    assert scaled["form_fields"] == {"scaleFactor": 0.8, "pageSize": "A4"}
    assert redacted["endpoint"] == "/api/v1/security/auto-redact"
    assert redacted["form_fields"]["listOfText"] == "secret\ncase [0-9]+"
    assert redacted["form_fields"]["redactColor"] == "000000"


def test_page_composition_wrappers_build_expected_requests():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]

    reorganized = _json_payload(
        registry.call_tool("stirling_reorganize_pages", {"pdf_path": str(_FIXTURE_PDF), "page_numbers": "3, 1"})
    )
    overlaid = _json_payload(
        registry.call_tool(
            "stirling_overlay_pdfs",
            {
                "pdf_path": str(_FIXTURE_PDF),
                "overlay_pdf_paths": [str(_FIXTURE_PDF)],
                "overlay_mode": "FixedRepeatOverlay",
                "counts": [2],
            },
        )
    )
    layout = _json_payload(registry.call_tool("stirling_page_layout", {"pdf_path": str(_FIXTURE_PDF)}))
    booklet = _json_payload(
        registry.call_tool("stirling_booklet_pdf", {"pdf_path": str(_FIXTURE_PDF), "add_gutter": True})
    )

    assert reorganized["form_fields"] == {"pageNumbers": "3,1"}
    assert overlaid["endpoint"] == "/api/v1/general/overlay-pdfs"
    assert overlaid["extra_file_fields"] == {"overlayFiles": [str(_FIXTURE_PDF)]}
    assert overlaid["form_fields"]["counts"] == [2]
    assert layout["endpoint"] == "/api/v1/general/multi-page-layout"
    assert layout["form_fields"] == {"pagesPerSheet": 4, "addBorder": False}
    assert booklet["endpoint"] == "/api/v1/general/booklet-imposition"
    assert booklet["form_fields"]["addGutter"] is True


def test_redaction_wrapper_requires_confirmation():
    registry = StirlingMcpToolRegistry()

    with pytest.raises(RuntimeError, match="requires confirmed=true"):
        registry.call_tool("stirling_redact_pdf", {"pdf_path": str(_FIXTURE_PDF), "words_to_redact": ["secret"]})


def test_signing_wrappers_build_expected_requests():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]

    visual = _json_payload(
        registry.call_tool(
            "stirling_sign_pdf",
            {"pdf_path": str(_FIXTURE_PDF), "signature_type": "text", "signer_name": "Ada", "confirmed": True},
        )
    )
    cert = _json_payload(
        registry.call_tool(
            "stirling_cert_sign_pdf",
            {
                "pdf_path": str(_FIXTURE_PDF),
                "cert_type": "PEM",
                "private_key_path": str(_FIXTURE_PDF),
                "cert_path": str(_FIXTURE_PDF),
                "confirmed": True,
                "show_signature": True,
            },
        )
    )

    assert visual["endpoint"] == "/api/v1/security/add-signature"
    assert visual["form_fields"] == {"signatureType": "text", "signerName": "Ada"}
    assert cert["endpoint"] == "/api/v1/security/cert-sign"
    assert cert["extra_file_fields"] == {"privateKeyFile": str(_FIXTURE_PDF), "certFile": str(_FIXTURE_PDF)}
    assert cert["form_fields"]["certType"] == "PEM"
    assert cert["form_fields"]["showSignature"] is True


def test_metadata_permissions_and_unlock_wrappers_build_expected_requests():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]

    metadata = _json_payload(
        registry.call_tool(
            "stirling_change_metadata",
            {
                "pdf_path": str(_FIXTURE_PDF),
                "title": "Report",
                "custom_metadata": [{"key": "Case", "value": "123"}],
            },
        )
    )
    permissions = _json_payload(
        registry.call_tool(
            "stirling_change_permissions",
            {"pdf_path": str(_FIXTURE_PDF), "prevent_printing": True, "confirmed": True},
        )
    )
    removed = _json_payload(
        registry.call_tool(
            "stirling_remove_certificate_signatures",
            {"pdf_path": str(_FIXTURE_PDF), "confirmed": True},
        )
    )
    unlocked = _json_payload(
        registry.call_tool("stirling_unlock_pdf_forms", {"pdf_path": str(_FIXTURE_PDF), "confirmed": True})
    )

    assert metadata["endpoint"] == "/api/v1/misc/update-metadata"
    assert metadata["form_fields"]["allRequestParams[customKey1]"] == "Case"
    assert metadata["form_fields"]["allRequestParams[customValue1]"] == "123"
    assert permissions["endpoint"] == "/api/v1/security/add-password"
    assert permissions["form_fields"]["preventPrinting"] is True
    assert removed["endpoint"] == "/api/v1/security/remove-cert-sign"
    assert unlocked["endpoint"] == "/api/v1/misc/unlock-pdf-forms"


def test_attachment_toc_cleanup_wrappers_build_expected_requests():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]

    attachments = _json_payload(
        registry.call_tool(
            "stirling_add_attachments",
            {
                "pdf_path": str(_FIXTURE_PDF),
                "attachment_paths": [str(_FIXTURE_PDF)],
                "convert_to_pdfa3b": True,
            },
        )
    )
    toc = _json_payload(
        registry.call_tool(
            "stirling_edit_table_of_contents",
            {
                "pdf_path": str(_FIXTURE_PDF),
                "bookmarks": [
                    {
                        "title": "Chapter 1",
                        "page_number": 1,
                        "children": [{"title": "Section 1.1", "page_number": 2}],
                    }
                ],
            },
        )
    )
    blanks = _json_payload(
        registry.call_tool("stirling_remove_blank_pages", {"pdf_path": str(_FIXTURE_PDF), "threshold": 12})
    )
    scans = _json_payload(
        registry.call_tool("stirling_split_scanned_photos", {"pdf_path": str(_FIXTURE_PDF), "border_size": 3})
    )
    colors = _json_payload(
        registry.call_tool(
            "stirling_replace_colors",
            {
                "pdf_path": str(_FIXTURE_PDF),
                "replace_and_invert_option": "CUSTOM_COLOR",
                "text_color": "#111111",
                "background_color": "#eeeeee",
            },
        )
    )

    assert attachments["endpoint"] == "/api/v1/misc/add-attachments"
    assert attachments["extra_file_fields"] == {"attachments": [str(_FIXTURE_PDF)]}
    assert attachments["form_fields"] == {"convertToPdfA3b": True}
    assert toc["endpoint"] == "/api/v1/general/edit-table-of-contents"
    assert json.loads(toc["form_fields"]["bookmarkData"]) == [
        {"title": "Chapter 1", "pageNumber": 1, "children": [{"title": "Section 1.1", "pageNumber": 2, "children": []}]}
    ]
    assert blanks["endpoint"] == "/api/v1/misc/remove-blanks"
    assert blanks["form_fields"] == {"threshold": 12, "whitePercent": 99.9}
    assert scans["endpoint"] == "/api/v1/misc/extract-image-scans"
    assert scans["form_fields"]["border_size"] == 3.0
    assert colors["endpoint"] == "/api/v1/misc/replace-invert-pdf"
    assert colors["form_fields"] == {
        "replaceAndInvertOption": "CUSTOM_COLOR",
        "textColor": "#111111",
        "backGroundColor": "#eeeeee",
    }


def test_replace_colors_validates_option():
    registry = StirlingMcpToolRegistry()

    with pytest.raises(RuntimeError, match="replace_and_invert_option must be"):
        registry.call_tool(
            "stirling_replace_colors",
            {"pdf_path": str(_FIXTURE_PDF), "replace_and_invert_option": "NOT_A_MODE"},
        )


def test_security_wrappers_require_confirmation():
    registry = StirlingMcpToolRegistry()

    with pytest.raises(RuntimeError, match="signing requires confirmed=true"):
        registry.call_tool("stirling_sign_pdf", {"pdf_path": str(_FIXTURE_PDF), "signer_name": "Ada"})
    with pytest.raises(RuntimeError, match="Permission changes require confirmed=true"):
        registry.call_tool("stirling_change_permissions", {"pdf_path": str(_FIXTURE_PDF)})
    with pytest.raises(RuntimeError, match="form unlocking requires confirmed=true"):
        registry.call_tool("stirling_unlock_pdf_forms", {"pdf_path": str(_FIXTURE_PDF)})


def test_execute_operation_resolves_static_frontend_endpoint():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]
    payload = registry.call_tool(
        "stirling_execute_operation",
        {
            "operation_id": "crop",
            "file_paths": [str(_FIXTURE_PDF)],
            "form_fields": {"x": 1, "y": 2, "width": 100, "height": 100},
        },
    )
    parsed = _json_payload(payload)

    assert parsed["endpoint"] == "/api/v1/general/crop"
    assert parsed["file_paths"] == [str(_FIXTURE_PDF)]
    assert parsed["file_field_name"] == "fileInput"
    assert parsed["form_fields"] == {"x": 1, "y": 2, "width": 100, "height": 100}


def test_generic_endpoint_tools_require_confirmation_for_high_risk_endpoints():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]

    with pytest.raises(RuntimeError, match="requires confirmed=true"):
        registry.call_tool(
            "stirling_call_endpoint",
            {
                "endpoint": "/api/v1/security/auto-redact",
                "file_paths": [str(_FIXTURE_PDF)],
                "form_fields": {"listOfText": "secret"},
            },
        )
    with pytest.raises(RuntimeError, match="requires confirmed=true"):
        registry.call_tool(
            "stirling_execute_operation",
            {"operation_id": "redact", "file_paths": [str(_FIXTURE_PDF)], "form_fields": {"listOfText": "secret"}},
        )

    confirmed = _json_payload(
        registry.call_tool(
            "stirling_call_endpoint",
            {
                "endpoint": "/api/v1/security/auto-redact",
                "file_paths": [str(_FIXTURE_PDF)],
                "form_fields": {"listOfText": "secret"},
                "confirmed": True,
            },
        )
    )

    assert confirmed["endpoint"] == "/api/v1/security/auto-redact"


def test_compress_accepts_friendly_file_size_alias():
    class FakeExecutor:
        def call_endpoint(self, **kwargs):
            return kwargs

    registry = StirlingMcpToolRegistry(endpoint_executor=FakeExecutor())  # type: ignore[arg-type]
    payload = registry.call_tool(
        "stirling_compress_pdf",
        {"pdf_path": str(_FIXTURE_PDF), "compression_method": "file_size", "expected_output_size": "10MB"},
    )
    parsed = _json_payload(payload)

    assert parsed["form_fields"]["expectedOutputSize"] == "10MB"


def test_filename_from_headers_strips_path_segments():
    executor = MultipartEndpointExecutor(output_dir=str(_REPO_ROOT / "engine" / "output"))

    filename = executor._filename_from_headers({"Content-Disposition": 'attachment; filename="../nested/evil.pdf"'})

    assert filename == "evil.pdf"


def test_multipart_upload_streams_by_default(monkeypatch: MonkeyPatch):
    monkeypatch.delenv("STIRLING_MCP_MULTIPART_MODE", raising=False)
    executor = MultipartEndpointExecutor(output_dir=str(_REPO_ROOT / "engine" / "output"))
    monkeypatch.setattr(
        executor,
        "_write_multipart_body",
        lambda form_fields, files: (_ for _ in ()).throw(AssertionError("spool path should not be used")),
    )

    body, boundary, body_size = executor._open_multipart_body({"angle": 90}, [("fileInput", _FIXTURE_PDF)])
    try:
        first_chunk = body.read(64)
    finally:
        executor._close_multipart_body(body)

    assert body_size is None
    assert boundary.startswith("stirling-mcp-")
    assert first_chunk.startswith(b"--stirling-mcp-")


def test_multipart_upload_can_spool_for_compatibility(monkeypatch: MonkeyPatch):
    monkeypatch.setenv("STIRLING_MCP_MULTIPART_MODE", "spool")
    fake_body_path = _FakeBodyPath()
    executor = MultipartEndpointExecutor(output_dir=str(_REPO_ROOT / "engine" / "output"))
    monkeypatch.setattr(
        executor,
        "_write_multipart_body",
        lambda form_fields, files: (fake_body_path, "test-boundary", len(fake_body_path.body)),
    )

    body, boundary, body_size = executor._open_multipart_body({"angle": 90}, [("fileInput", _FIXTURE_PDF)])
    executor._close_multipart_body(body)

    assert boundary == "test-boundary"
    assert body_size == len(fake_body_path.body)
    assert fake_body_path.closed is True
