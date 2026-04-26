from __future__ import annotations

import json
import mimetypes
import os
import re
import shutil
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any

from pydantic import BaseModel, Field, ValidationError

import models

if TYPE_CHECKING:
    from file_processing_agent import ToolCatalogService

type JsonPrimitive = str | int | float | bool | None
type JsonObject = dict[str, "JsonValue"]
type JsonArray = list["JsonValue"]
type JsonValue = JsonPrimitive | JsonObject | JsonArray

_MCP_README_PATH = Path(__file__).resolve().parents[1] / "MCP.md"
_REPO_ROOT = Path(__file__).resolve().parents[2]
_DEFAULT_OUTPUT_DIR = Path(__file__).resolve().parent / "output"
_DEFAULT_BACKEND_HEALTH_PATHS = (
    "/api/v1/info/health",
    "/api/v1/info/status",
    "/actuator/health",
    "/health",
    "/healthz",
)
_CONFIG_REQUIRED_ENV = (
    "STIRLING_LOG_PATH",
    "STIRLING_PDF_TAURI_MODE",
    "STIRLING_OPENAI_BASE_URL",
    "STIRLING_ANTHROPIC_API_KEY",
    "STIRLING_JAVA_BACKEND_URL",
    "STIRLING_JAVA_BACKEND_API_KEY",
    "STIRLING_JAVA_REQUEST_TIMEOUT_SECONDS",
    "STIRLING_SMART_MODEL",
    "STIRLING_FAST_MODEL",
    "STIRLING_SMART_MODEL_REASONING_EFFORT",
    "STIRLING_FAST_MODEL_REASONING_EFFORT",
    "STIRLING_SMART_MODEL_TEXT_VERBOSITY",
    "STIRLING_FAST_MODEL_TEXT_VERBOSITY",
    "STIRLING_AI_MAX_TOKENS",
    "STIRLING_SMART_MODEL_MAX_TOKENS",
    "STIRLING_FAST_MODEL_MAX_TOKENS",
    "STIRLING_CLAUDE_MAX_TOKENS",
    "STIRLING_DEFAULT_MODEL_MAX_TOKENS",
    "STIRLING_POSTHOG_API_KEY",
    "STIRLING_POSTHOG_HOST",
    "STIRLING_FLASK_DEBUG",
    "STIRLING_AI_STREAMING",
    "STIRLING_AI_PREVIEW_MAX_INFLIGHT",
    "STIRLING_AI_REQUEST_TIMEOUT",
    "STIRLING_AI_RAW_DEBUG",
    "STIRLING_PDF_EDITOR_TABLE_DEBUG",
)
_AI_PROVIDER_ENV = ("STIRLING_OPENAI_API_KEY", "STIRLING_ANTHROPIC_API_KEY")


class McpToolError(RuntimeError):
    pass


def _normalize_json_value(value: Any) -> JsonValue:
    return json.loads(json.dumps(value, ensure_ascii=True))


def _env_value(name: str) -> str:
    return os.environ.get(name, "")


def _java_backend_url(path: str) -> str:
    base = _env_value("STIRLING_JAVA_BACKEND_URL").rstrip("/")
    if not base:
        raise McpToolError("STIRLING_JAVA_BACKEND_URL is not configured.")
    if not path.startswith("/"):
        path = "/" + path
    return f"{base}{path}"


def _java_backend_headers() -> dict[str, str]:
    headers: dict[str, str] = {}
    api_key = _env_value("STIRLING_JAVA_BACKEND_API_KEY")
    if api_key:
        headers["X-API-KEY"] = api_key
    return headers


def _java_request_timeout_seconds() -> float:
    raw_value = _env_value("STIRLING_JAVA_REQUEST_TIMEOUT_SECONDS") or "30"
    try:
        return float(raw_value)
    except ValueError as exc:
        raise McpToolError("STIRLING_JAVA_REQUEST_TIMEOUT_SECONDS must be a number.") from exc


def _get_pdf_preflight(file_path: str) -> models.PdfPreflight:
    from editing.operations import get_pdf_preflight

    return get_pdf_preflight(file_path)


def _validate_operation_chain(operation_ids: list[models.tool_models.OperationId]) -> Any:
    from editing.operations import validate_operation_chain

    return validate_operation_chain(operation_ids)


def _assess_plan_risk(
    operation_ids: list[models.tool_models.OperationId],
    preflight: models.PdfPreflight | None,
) -> dict[str, Any]:
    from editing.constants import assess_plan_risk

    return assess_plan_risk(operation_ids, preflight)


def _build_plan_summary(operation_ids: list[models.tool_models.OperationId]) -> Any:
    from editing.operations import build_plan_summary

    return build_plan_summary(operation_ids)


def _answer_pdf_question(file_path: str, question: str) -> str:
    from editing.operations import answer_pdf_question

    return answer_pdf_question(file_path, question)


@dataclass(frozen=True)
class OperationFrontendMetadata:
    operation_id: str
    source_file: str
    tool_type: str | None
    endpoint_expression: str | None
    build_form_data_name: str | None
    build_form_data_source: str | None
    custom_processor_name: str | None
    custom_processor_source: str | None

    def to_dict(self) -> dict[str, JsonValue]:
        return {
            "operationId": self.operation_id,
            "sourceFile": self.source_file,
            "toolType": self.tool_type,
            "endpointExpression": self.endpoint_expression,
            "buildFormDataName": self.build_form_data_name,
            "buildFormDataSource": self.build_form_data_source,
            "customProcessorName": self.custom_processor_name,
            "customProcessorSource": self.custom_processor_source,
        }


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    input_model: type[BaseModel]


@dataclass(frozen=True)
class StaticToolCatalog:
    operation_ids: list[models.tool_models.OperationId]


class StaticToolCatalogService:
    def get_catalog(self) -> StaticToolCatalog:
        return StaticToolCatalog(operation_ids=sorted(models.tool_models.OPERATIONS.keys()))

    def get_operation(
        self,
        operation_id: models.tool_models.OperationId,
    ) -> models.tool_models.ParamToolModelType | None:
        return models.tool_models.OPERATIONS.get(operation_id)


class NoArgs(BaseModel):
    pass


class HealthCheckArgs(BaseModel):
    backend_health_paths: list[str] = Field(
        default_factory=lambda: list(_DEFAULT_BACKEND_HEALTH_PATHS),
        description="Backend health paths to try, relative to STIRLING_JAVA_BACKEND_URL.",
    )
    run_backend_operation_probe: bool = Field(
        default=True,
        description="Whether to POST a small rotate-pdf request using the repo test PDF when it is available.",
    )
    run_ai_provider_probe: bool = Field(
        default=False,
        description="Reserved for live AI-provider checks. Defaults off because it may incur network calls or cost.",
    )


class GetOperationDetailsArgs(BaseModel):
    operation_id: str = Field(description="Stirling operation id, for example 'rotate' or 'merge'.")


class PlanEditRequestArgs(BaseModel):
    request: str = Field(description="Natural-language PDF edit request.")
    file_paths: list[str] = Field(
        default_factory=list,
        description="Optional local file paths that provide file context for planning.",
    )
    history: list[models.ChatMessage] = Field(
        default_factory=list,
        description="Optional prior conversation history in the same format the engine uses.",
    )


class AnswerPdfQuestionArgs(BaseModel):
    pdf_path: str = Field(description="Absolute or relative path to a local PDF file.")
    question: str = Field(description="Question to answer from the PDF text.")


class ReadPdfEditorDocumentArgs(BaseModel):
    pdf_path: str = Field(description="Absolute or relative path to a local PDF file.")


class CallEndpointArgs(BaseModel):
    endpoint: str = Field(description="Backend endpoint path starting with /api/v1/.")
    file_paths: list[str] = Field(
        default_factory=list,
        description="Primary local files to attach under file_field_name.",
    )
    file_field_name: str = Field(
        default="fileInput",
        description="Multipart field name used for the primary files.",
    )
    extra_file_fields: dict[str, str | list[str]] = Field(
        default_factory=dict,
        description="Additional multipart file fields, for example watermarkImage or overlayFiles.",
    )
    form_fields: dict[str, JsonValue] = Field(
        default_factory=dict,
        description="Non-file multipart fields. Booleans are sent as lowercase true/false strings.",
    )
    output_path: str | None = Field(
        default=None,
        description="Optional destination path for binary responses. Defaults to engine/src/output/mcp/.",
    )
    async_job: bool = Field(
        default=False,
        description="Submit AutoJob endpoints with ?async=true and return the job id/status response.",
    )
    wait_for_job: bool = Field(
        default=False,
        description="When async_job is true, poll until the job completes and fetch the final result.",
    )
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class JobStatusArgs(BaseModel):
    job_id: str = Field(description="Stirling backend job id.")
    fetch_result: bool = Field(default=False, description="Fetch the final result if the job is complete.")
    output_path: str | None = Field(default=None, description="Optional destination path for binary job results.")


class RotatePdfArgs(BaseModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    angle: int = Field(default=90, description="Rotation angle. Must be a multiple of 90.")
    output_path: str | None = Field(default=None, description="Optional destination path for the rotated PDF.")
    async_job: bool = Field(default=False, description="Submit as a backend async job.")
    wait_for_job: bool = Field(default=False, description="Poll and fetch the result when async_job is true.")
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class MergePdfsArgs(BaseModel):
    pdf_paths: list[str] = Field(min_length=2, description="PDF files to merge in the provided order.")
    remove_digital_signature: bool = Field(default=False, description="Remove certificate signatures before merge.")
    generate_table_of_contents: bool = Field(default=False, description="Generate a table of contents in the merged PDF.")
    output_path: str | None = Field(default=None, description="Optional destination path for the merged PDF.")
    async_job: bool = Field(default=False, description="Submit as a backend async job.")
    wait_for_job: bool = Field(default=False, description="Poll and fetch the result when async_job is true.")
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class CompressPdfArgs(BaseModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    compression_method: str = Field(default="quality", description="Compression method: quality or fileSize.")
    compression_level: int = Field(default=3, ge=1, le=5, description="Optimize level for quality compression.")
    expected_output_size: str | None = Field(
        default=None,
        description="Target output size for fileSize compression, for example 10MB.",
    )
    grayscale: bool = False
    line_art: bool = False
    linearize: bool = False
    line_art_threshold: int = Field(default=180, ge=0, le=255)
    line_art_edge_level: float = Field(default=0.5, ge=0.0, le=1.0)
    output_path: str | None = Field(default=None, description="Optional destination path for the compressed PDF.")
    async_job: bool = Field(default=False, description="Submit as a backend async job.")
    wait_for_job: bool = Field(default=False, description="Poll and fetch the result when async_job is true.")
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class RemovePagesArgs(BaseModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    page_numbers: str = Field(description="Pages or ranges to remove, for example 1,3,5-7.")
    output_path: str | None = Field(default=None, description="Optional destination path for the result PDF.")
    async_job: bool = Field(default=False, description="Submit as a backend async job.")
    wait_for_job: bool = Field(default=False, description="Poll and fetch the result when async_job is true.")
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class FrontendOperationMetadataResolver:
    def __init__(self, repo_root: Path | None = None) -> None:
        self.repo_root = repo_root or _REPO_ROOT
        self._cache: dict[str, OperationFrontendMetadata] | None = None

    def get(self, operation_id: str) -> OperationFrontendMetadata | None:
        return self._metadata().get(operation_id)

    def list_all(self) -> list[OperationFrontendMetadata]:
        return [self._metadata()[key] for key in sorted(self._metadata())]

    def _metadata(self) -> dict[str, OperationFrontendMetadata]:
        if self._cache is None:
            self._cache = self._scan()
        return self._cache

    def _scan(self) -> dict[str, OperationFrontendMetadata]:
        frontend_root = self.repo_root / "frontend" / "src"
        candidates = sorted(
            [
                path
                for path in frontend_root.rglob("*Operation.ts")
                if "node_modules" not in path.parts and ".test." not in path.name
            ],
            key=self._path_priority,
        )
        found: dict[str, OperationFrontendMetadata] = {}
        for path in candidates:
            source = path.read_text(encoding="utf-8", errors="replace")
            operation_id = self._extract_operation_id(source)
            if not operation_id or operation_id in found:
                continue
            found[operation_id] = self._build_metadata(operation_id, path, source)
        return found

    def _path_priority(self, path: Path) -> tuple[int, str]:
        priority = 2
        path_text = path.as_posix()
        if "/core/" in path_text:
            priority = 0
        elif "/desktop/" in path_text or "/proprietary/" in path_text:
            priority = 1
        return (priority, path_text)

    def _build_metadata(self, operation_id: str, path: Path, source: str) -> OperationFrontendMetadata:
        endpoint_expression = self._extract_config_value(source, "endpoint")
        build_form_data_name = self._extract_identifier_config_value(source, "buildFormData")
        custom_processor_name = self._extract_identifier_config_value(source, "customProcessor")
        return OperationFrontendMetadata(
            operation_id=operation_id,
            source_file=str(path.relative_to(self.repo_root)).replace("\\", "/"),
            tool_type=self._extract_tool_type(source),
            endpoint_expression=endpoint_expression,
            build_form_data_name=build_form_data_name,
            build_form_data_source=self._extract_named_source(source, build_form_data_name),
            custom_processor_name=custom_processor_name,
            custom_processor_source=self._extract_named_source(source, custom_processor_name),
        )

    def _extract_operation_id(self, source: str) -> str | None:
        match = re.search(r"operationType\s*:\s*['\"]([A-Za-z0-9_]+)['\"]", source)
        return match.group(1) if match else None

    def _extract_tool_type(self, source: str) -> str | None:
        match = re.search(r"toolType\s*:\s*ToolType\.([A-Za-z_][A-Za-z0-9_]*)", source)
        return match.group(1) if match else None

    def _extract_config_value(self, source: str, name: str) -> str | None:
        key = f"{name}:"
        index = source.find(key)
        if index == -1:
            return None
        start = index + len(key)
        while start < len(source) and source[start].isspace():
            start += 1
        end = self._find_config_value_end(source, start)
        return source[start:end].strip() or None

    def _extract_identifier_config_value(self, source: str, name: str) -> str | None:
        value = self._extract_config_value(source, name)
        if value and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", value):
            return value
        return None

    def _find_config_value_end(self, source: str, start: int) -> int:
        depth_paren = 0
        depth_brace = 0
        depth_bracket = 0
        quote: str | None = None
        i = start
        while i < len(source):
            ch = source[i]
            if quote is not None:
                if ch == "\\":
                    i += 2
                    continue
                if ch == quote:
                    quote = None
                i += 1
                continue
            if ch in {"'", '"', "`"}:
                quote = ch
            elif ch == "(":
                depth_paren += 1
            elif ch == ")":
                depth_paren -= 1
            elif ch == "{":
                depth_brace += 1
            elif ch == "}":
                if depth_brace == 0:
                    return i
                depth_brace -= 1
            elif ch == "[":
                depth_bracket += 1
            elif ch == "]":
                depth_bracket -= 1
            elif ch == "," and depth_paren == 0 and depth_brace == 0 and depth_bracket == 0:
                return i
            i += 1
        return i

    def _extract_named_source(self, source: str, name: str | None) -> str | None:
        if not name:
            return None
        const_match = re.search(rf"(?:export\s+)?const\s+{re.escape(name)}\b[^=]*=\s*", source)
        if const_match:
            start = const_match.start()
            expr_start = const_match.end()
            end = self._find_expression_end(source, expr_start)
            return source[start:end].strip()
        fn_match = re.search(rf"(?:export\s+)?function\s+{re.escape(name)}\s*\(", source)
        if fn_match:
            brace_start = source.find("{", fn_match.end())
            if brace_start == -1:
                return None
            brace_end = self._find_matching(source, brace_start, "{", "}")
            return source[fn_match.start() : brace_end + 1].strip()
        return None

    def _find_expression_end(self, source: str, start: int) -> int:
        depth_paren = 0
        depth_brace = 0
        depth_bracket = 0
        quote: str | None = None
        i = start
        while i < len(source):
            ch = source[i]
            if quote is not None:
                if ch == "\\":
                    i += 2
                    continue
                if ch == quote:
                    quote = None
                i += 1
                continue
            if ch in {"'", '"', "`"}:
                quote = ch
            elif ch == "(":
                depth_paren += 1
            elif ch == ")":
                depth_paren -= 1
            elif ch == "{":
                depth_brace += 1
            elif ch == "}":
                depth_brace -= 1
            elif ch == "[":
                depth_bracket += 1
            elif ch == "]":
                depth_bracket -= 1
            elif ch == ";" and depth_paren == 0 and depth_brace == 0 and depth_bracket == 0:
                return i + 1
            i += 1
        return i

    def _find_matching(self, text: str, start: int, open_char: str, close_char: str) -> int:
        depth = 0
        quote: str | None = None
        i = start
        while i < len(text):
            ch = text[i]
            if quote is not None:
                if ch == "\\":
                    i += 2
                    continue
                if ch == quote:
                    quote = None
                i += 1
                continue
            if ch in {"'", '"', "`"}:
                quote = ch
            elif ch == open_char:
                depth += 1
            elif ch == close_char:
                depth -= 1
                if depth == 0:
                    return i
            i += 1
        raise McpToolError(f"Unmatched {open_char}{close_char} block while parsing frontend metadata.")


class MultipartStreamingBody:
    def __init__(
        self,
        boundary: str,
        form_fields: dict[str, JsonValue],
        files: list[tuple[str, Path]],
        field_normalizer: Any,
        chunk_size: int = 1024 * 1024,
    ) -> None:
        self.chunk_size = chunk_size
        self._parts: list[bytes | Path] = self._build_parts(boundary, form_fields, files, field_normalizer)
        self._part_index = 0
        self._bytes_part_offset = 0
        self._file_handle: Any | None = None

    def read(self, size: int = -1) -> bytes:
        target_size = self.chunk_size if size is None or size < 0 else size
        if target_size == 0:
            return b""
        output = bytearray()
        while len(output) < target_size and self._part_index < len(self._parts):
            part = self._parts[self._part_index]
            if isinstance(part, bytes):
                output.extend(self._read_bytes_part(part, target_size - len(output)))
            else:
                output.extend(self._read_file_part(part, target_size - len(output)))
        return bytes(output)

    def close(self) -> None:
        if self._file_handle is not None:
            self._file_handle.close()
            self._file_handle = None

    def _read_bytes_part(self, part: bytes, size: int) -> bytes:
        chunk = part[self._bytes_part_offset : self._bytes_part_offset + size]
        self._bytes_part_offset += len(chunk)
        if self._bytes_part_offset >= len(part):
            self._part_index += 1
            self._bytes_part_offset = 0
        return chunk

    def _read_file_part(self, part: Path, size: int) -> bytes:
        if self._file_handle is None:
            self._file_handle = part.open("rb")
        chunk = self._file_handle.read(size)
        if not chunk:
            self.close()
            self._part_index += 1
            return b""
        return chunk

    def _build_parts(
        self,
        boundary: str,
        form_fields: dict[str, JsonValue],
        files: list[tuple[str, Path]],
        field_normalizer: Any,
    ) -> list[bytes | Path]:
        parts: list[bytes | Path] = []
        for field_name, value in form_fields.items():
            for normalized in field_normalizer(value):
                parts.append(
                    (
                        f"--{boundary}\r\n"
                        f'Content-Disposition: form-data; name="{field_name}"\r\n\r\n'
                        f"{normalized}\r\n"
                    ).encode()
                )
        for field_name, path in files:
            mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
            parts.append(
                (
                    f"--{boundary}\r\n"
                    f'Content-Disposition: form-data; name="{field_name}"; filename="{path.name}"\r\n'
                    f"Content-Type: {mime_type}\r\n\r\n"
                ).encode()
            )
            parts.append(path)
            parts.append(b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        return parts


class MultipartEndpointExecutor:
    def __init__(self, output_dir: str | Path | None = None) -> None:
        self.output_dir = (Path(output_dir) if output_dir is not None else _DEFAULT_OUTPUT_DIR).resolve()
        self.allowed_roots = self._allowed_roots()

    def call_endpoint(
        self,
        endpoint: str,
        file_paths: list[str],
        file_field_name: str,
        extra_file_fields: dict[str, str | list[str]],
        form_fields: dict[str, JsonValue],
        output_path: str | None,
        async_job: bool = False,
        wait_for_job: bool = False,
        poll_interval_seconds: float = 1.0,
        poll_timeout_seconds: float = 120.0,
    ) -> dict[str, JsonValue]:
        if not endpoint.startswith("/api/v1/"):
            raise McpToolError("endpoint must start with /api/v1/.")

        primary_files = [(file_field_name, self._resolve_file_path(file_path)) for file_path in file_paths]
        additional_files: list[tuple[str, Path]] = []
        for field_name, value in extra_file_fields.items():
            values = value if isinstance(value, list) else [value]
            additional_files.extend((field_name, self._resolve_file_path(item)) for item in values)

        body, boundary, body_size = self._open_multipart_body(form_fields, primary_files + additional_files)
        request_endpoint = f"{endpoint}?async=true" if async_job else endpoint
        headers = self._multipart_headers(boundary, body_size)
        try:
            request = urllib.request.Request(
                _java_backend_url(request_endpoint),
                data=body,
                headers=headers,
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=_java_request_timeout_seconds()) as response:
                result = self._handle_response(response, endpoint, output_path)
            if async_job and wait_for_job:
                job_id = self._extract_job_id(result)
                if not job_id:
                    raise McpToolError("Async backend response did not include a job id.")
                return self.wait_for_job(job_id, output_path, poll_interval_seconds, poll_timeout_seconds)
            return result
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
            raise McpToolError(f"Backend request failed with status {exc.code}: {detail or exc.reason}") from exc
        except urllib.error.URLError as exc:
            raise McpToolError(f"Failed to reach Java backend: {exc.reason}") from exc
        finally:
            self._close_multipart_body(body)

    def get_job_status(self, job_id: str) -> dict[str, JsonValue]:
        response = self._get_json(f"/api/v1/general/job/{urllib.parse.quote(job_id)}")
        return {"jobId": job_id, "status": response}

    def get_job_result(self, job_id: str, output_path: str | None) -> dict[str, JsonValue]:
        endpoint = f"/api/v1/general/job/{urllib.parse.quote(job_id)}/result"
        request = urllib.request.Request(_java_backend_url(endpoint), headers=_java_backend_headers(), method="GET")
        try:
            with urllib.request.urlopen(request, timeout=_java_request_timeout_seconds()) as response:
                return self._handle_response(response, endpoint, output_path)
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
            raise McpToolError(f"Backend job result failed with status {exc.code}: {detail or exc.reason}") from exc
        except urllib.error.URLError as exc:
            raise McpToolError(f"Failed to reach Java backend: {exc.reason}") from exc

    def wait_for_job(
        self,
        job_id: str,
        output_path: str | None,
        poll_interval_seconds: float,
        poll_timeout_seconds: float,
    ) -> dict[str, JsonValue]:
        deadline = time.monotonic() + poll_timeout_seconds
        last_status: dict[str, JsonValue] | None = None
        while time.monotonic() <= deadline:
            last_status = self.get_job_status(job_id)
            if self._job_is_complete(last_status):
                result = self.get_job_result(job_id, output_path)
                return {"jobId": job_id, "status": last_status["status"], "result": result}
            time.sleep(poll_interval_seconds)
        return {"jobId": job_id, "status": last_status or {}, "timedOut": True}

    def _get_json(self, endpoint: str) -> JsonValue:
        request = urllib.request.Request(_java_backend_url(endpoint), headers=_java_backend_headers(), method="GET")
        try:
            with urllib.request.urlopen(request, timeout=_java_request_timeout_seconds()) as response:
                raw = response.read()
                return _normalize_json_value(json.loads(raw.decode("utf-8"))) if raw else {}
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
            raise McpToolError(f"Backend request failed with status {exc.code}: {detail or exc.reason}") from exc
        except urllib.error.URLError as exc:
            raise McpToolError(f"Failed to reach Java backend: {exc.reason}") from exc

    def _handle_response(self, response: Any, endpoint: str, output_path: str | None) -> dict[str, JsonValue]:
        content_type = response.headers.get("Content-Type", "application/octet-stream")
        if "application/json" in content_type:
            raw = response.read()
            text = raw.decode("utf-8") if raw else ""
            parsed = json.loads(text) if text else {}
            return {
                "endpoint": endpoint,
                "contentType": content_type,
                "resultJson": _normalize_json_value(parsed),
            }

        destination = self._resolve_output_path(output_path, response.headers, content_type)
        destination.parent.mkdir(parents=True, exist_ok=True)
        size_bytes = 0
        with destination.open("wb") as output_handle:
            while chunk := response.read(1024 * 1024):
                size_bytes += len(chunk)
                output_handle.write(chunk)
        return {
            "endpoint": endpoint,
            "contentType": content_type,
            "savedPath": str(destination),
            "sizeBytes": size_bytes,
        }

    def _extract_job_id(self, result: dict[str, JsonValue]) -> str | None:
        result_json = result.get("resultJson")
        if not isinstance(result_json, dict):
            return None
        for key in ("jobId", "jobID", "id"):
            value = result_json.get(key)
            if isinstance(value, str):
                return value
        return None

    def _job_is_complete(self, status: dict[str, JsonValue]) -> bool:
        payload = status.get("status")
        if isinstance(payload, dict):
            job_result = payload.get("jobResult")
            if isinstance(job_result, dict):
                return bool(job_result.get("complete") or job_result.get("isComplete"))
            return bool(payload.get("complete") or payload.get("isComplete"))
        return False

    def _resolve_file_path(self, file_path: str) -> Path:
        path = Path(file_path).expanduser()
        if not path.is_absolute():
            path = Path.cwd() / path
        path = path.resolve()
        self._ensure_allowed_path(path, "input file")
        if not path.exists():
            raise McpToolError(f"File not found: {path}")
        if not path.is_file():
            raise McpToolError(f"Path is not a file: {path}")
        return path

    def _write_multipart_body(
        self,
        form_fields: dict[str, JsonValue],
        files: list[tuple[str, Path]],
    ) -> tuple[Path, str, int]:
        boundary = f"stirling-mcp-{uuid.uuid4().hex}"
        temp_root = self.output_dir / "mcp" / "tmp"
        temp_root.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(prefix="multipart-", suffix=".bin", dir=temp_root, delete=False) as handle:
            body_path = Path(handle.name)
            for field_name, value in form_fields.items():
                for normalized in self._normalize_field_values(value):
                    handle.write(f"--{boundary}\r\n".encode())
                    handle.write(f'Content-Disposition: form-data; name="{field_name}"\r\n\r\n'.encode())
                    handle.write(normalized.encode("utf-8"))
                    handle.write(b"\r\n")
            for field_name, path in files:
                mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
                handle.write(f"--{boundary}\r\n".encode())
                handle.write(
                    (
                        f'Content-Disposition: form-data; name="{field_name}"; filename="{path.name}"\r\n'
                        f"Content-Type: {mime_type}\r\n\r\n"
                    ).encode()
                )
                with path.open("rb") as file_handle:
                    shutil.copyfileobj(file_handle, handle, length=1024 * 1024)
                handle.write(b"\r\n")
            handle.write(f"--{boundary}--\r\n".encode())
        return (body_path, boundary, body_path.stat().st_size)

    def _open_multipart_body(self, form_fields: dict[str, JsonValue], files: list[tuple[str, Path]]) -> tuple[Any, str, int | None]:
        mode = self._multipart_mode()
        if mode == "spool":
            body_path, boundary, body_size = self._write_multipart_body(form_fields, files)
            return (body_path.open("rb"), boundary, body_size)
        if mode != "stream":
            raise McpToolError("STIRLING_MCP_MULTIPART_MODE must be 'stream' or 'spool'.")
        boundary = f"stirling-mcp-{uuid.uuid4().hex}"
        return (MultipartStreamingBody(boundary, form_fields, files, self._normalize_field_values), boundary, None)

    def _close_multipart_body(self, body: Any) -> None:
        name = getattr(body, "name", None)
        close = getattr(body, "close", None)
        if callable(close):
            close()
        if isinstance(name, str) and Path(name).name.startswith("multipart-"):
            Path(name).unlink(missing_ok=True)

    def _multipart_mode(self) -> str:
        return (_env_value("STIRLING_MCP_MULTIPART_MODE") or "stream").strip().lower()

    def _multipart_headers(self, boundary: str, body_size: int | None) -> dict[str, str]:
        headers = _java_backend_headers()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        if body_size is not None:
            headers["Content-Length"] = str(body_size)
        return headers

    def _normalize_field_values(self, value: JsonValue) -> list[str]:
        if value is None:
            return []
        if isinstance(value, bool):
            return [str(value).lower()]
        if isinstance(value, (str, int, float)):
            return [str(value)]
        if isinstance(value, list):
            normalized: list[str] = []
            for item in value:
                normalized.extend(self._normalize_field_values(item))
            return normalized
        return [json.dumps(value, ensure_ascii=True)]

    def _resolve_output_path(self, output_path: str | None, headers: Any, content_type: str) -> Path:
        if output_path:
            path = Path(output_path).expanduser()
            if not path.is_absolute():
                path = self.output_dir / "mcp" / path
            path = path.resolve()
            self._ensure_allowed_path(path, "output file")
            return path
        filename = self._filename_from_headers(headers) or self._default_filename(content_type)
        return self.output_dir / "mcp" / filename

    def _allowed_roots(self) -> list[Path]:
        configured = [item.strip() for item in _env_value("STIRLING_MCP_ALLOWED_ROOTS").split(os.pathsep) if item.strip()]
        roots = [Path(item).expanduser().resolve() for item in configured]
        roots.extend([_REPO_ROOT.resolve(), self.output_dir.resolve()])
        return list(dict.fromkeys(roots))

    def _ensure_allowed_path(self, path: Path, label: str) -> None:
        if any(path == root or root in path.parents for root in self.allowed_roots):
            return
        allowed = ", ".join(str(root) for root in self.allowed_roots)
        raise McpToolError(f"{label} is outside allowed MCP roots: {path}. Allowed roots: {allowed}")

    def _filename_from_headers(self, headers: Any) -> str | None:
        disposition = headers.get("Content-Disposition")
        if not disposition:
            return None
        match = re.search(r'filename="?([^";]+)"?', disposition)
        if not match:
            return None
        filename = Path(match.group(1).replace("\\", "/")).name
        return filename or None

    def _default_filename(self, content_type: str) -> str:
        extension = mimetypes.guess_extension(content_type.split(";")[0].strip()) or ".bin"
        return f"stirling-mcp-{uuid.uuid4().hex}{extension}"


class StirlingMcpHealthChecker:
    def run(self, args: HealthCheckArgs) -> dict[str, JsonValue]:
        checks: list[dict[str, JsonValue]] = [
            self._check_mcp_process(),
            self._check_environment(),
            self._check_pdf_tooling(),
            self._check_filesystem(),
            self._check_backend_health(args.backend_health_paths),
            self._check_backend_operation_probe(args.run_backend_operation_probe),
            self._check_ai_provider(args.run_ai_provider_probe),
        ]
        failing = [check for check in checks if check["status"] == "fail"]
        warning = [check for check in checks if check["status"] == "warn"]
        overall = "unhealthy" if failing else "degraded" if warning else "healthy"
        return {
            "status": overall,
            "server": {
                "name": StirlingMcpToolRegistry.SERVER_NAME,
                "version": StirlingMcpToolRegistry.SERVER_VERSION,
                "protocolVersion": StirlingMcpToolRegistry.PROTOCOL_VERSION,
            },
            "checks": _normalize_json_value(checks),
        }

    def _check_mcp_process(self) -> dict[str, JsonValue]:
        return {
            "name": "mcp.process",
            "status": "pass",
            "message": "MCP server process is running and handling tool calls.",
        }

    def _check_environment(self) -> dict[str, JsonValue]:
        missing = [name for name in _CONFIG_REQUIRED_ENV if name not in os.environ]
        empty_required = [
            name
            for name in (
                "STIRLING_JAVA_BACKEND_URL",
                "STIRLING_JAVA_REQUEST_TIMEOUT_SECONDS",
                "STIRLING_SMART_MODEL",
                "STIRLING_FAST_MODEL",
                "STIRLING_SMART_MODEL_REASONING_EFFORT",
                "STIRLING_FAST_MODEL_REASONING_EFFORT",
                "STIRLING_SMART_MODEL_TEXT_VERBOSITY",
                "STIRLING_FAST_MODEL_TEXT_VERBOSITY",
                "STIRLING_SMART_MODEL_MAX_TOKENS",
                "STIRLING_FAST_MODEL_MAX_TOKENS",
                "STIRLING_CLAUDE_MAX_TOKENS",
                "STIRLING_DEFAULT_MODEL_MAX_TOKENS",
                "STIRLING_POSTHOG_API_KEY",
                "STIRLING_POSTHOG_HOST",
                "STIRLING_FLASK_DEBUG",
                "STIRLING_AI_STREAMING",
                "STIRLING_AI_PREVIEW_MAX_INFLIGHT",
                "STIRLING_AI_REQUEST_TIMEOUT",
            )
            if name in os.environ and not _env_value(name)
        ]
        openai_configured = bool(_env_value("STIRLING_OPENAI_API_KEY"))
        anthropic_configured = bool(_env_value("STIRLING_ANTHROPIC_API_KEY"))
        provider_missing = not openai_configured and not anthropic_configured
        timeout_error = self._validate_float_env("STIRLING_JAVA_REQUEST_TIMEOUT_SECONDS")

        problems = []
        if missing:
            problems.append(f"missing={', '.join(missing)}")
        if empty_required:
            problems.append(f"empty={', '.join(empty_required)}")
        if provider_missing:
            problems.append("no AI provider key configured")
        if timeout_error:
            problems.append(timeout_error)

        return {
            "name": "engine.environment",
            "status": "fail" if problems else "pass",
            "message": "; ".join(problems) if problems else "Required engine environment is present.",
            "details": _normalize_json_value({
                "missingKeys": missing,
                "emptyRequiredKeys": empty_required,
                "openaiConfigured": openai_configured,
                "anthropicConfigured": anthropic_configured,
                "posthogConfigured": bool(_env_value("STIRLING_POSTHOG_API_KEY")),
                "javaBackendConfigured": bool(_env_value("STIRLING_JAVA_BACKEND_URL")),
            }),
        }

    def _validate_float_env(self, name: str) -> str | None:
        raw_value = _env_value(name)
        if not raw_value:
            return None
        try:
            float(raw_value)
        except ValueError:
            return f"{name} must be numeric"
        return None

    def _check_pdf_tooling(self) -> dict[str, JsonValue]:
        executable = shutil.which("pdftohtml")
        return {
            "name": "pdf.pdftohtml",
            "status": "pass" if executable else "fail",
            "message": f"pdftohtml found at {executable}" if executable else "pdftohtml is not available on PATH.",
            "details": {"path": executable},
        }

    def _check_filesystem(self) -> dict[str, JsonValue]:
        executor = MultipartEndpointExecutor()
        temp_root = executor.output_dir / "mcp" / "tmp"
        try:
            temp_root.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(prefix="health-", suffix=".tmp", dir=temp_root, delete=False) as handle:
                health_path = Path(handle.name)
                handle.write(b"ok")
            health_path.unlink(missing_ok=True)
        except OSError as exc:
            return {
                "name": "mcp.filesystem",
                "status": "fail",
                "message": f"MCP output/temp directory is not writable: {exc}",
                "details": {
                    "outputDir": str(executor.output_dir),
                    "tempDir": str(temp_root),
                    "allowedRoots": [str(path) for path in executor.allowed_roots],
                    "multipartMode": executor._multipart_mode(),
                },
            }
        return {
            "name": "mcp.filesystem",
            "status": "pass",
            "message": "MCP output/temp directory is writable.",
            "details": {
                "outputDir": str(executor.output_dir),
                "tempDir": str(temp_root),
                "allowedRoots": [str(path) for path in executor.allowed_roots],
                "multipartMode": executor._multipart_mode(),
            },
        }

    def _check_backend_health(self, paths: list[str]) -> dict[str, JsonValue]:
        if not _env_value("STIRLING_JAVA_BACKEND_URL"):
            return {
                "name": "backend.health",
                "status": "fail",
                "message": "STIRLING_JAVA_BACKEND_URL is not configured.",
                "details": {"attempts": []},
            }

        attempts: JsonArray = []
        for path in paths or list(_DEFAULT_BACKEND_HEALTH_PATHS):
            attempt = self._get_backend_health_path(path)
            attempts.append(attempt)
            if attempt.get("healthy") is True:
                return {
                    "name": "backend.health",
                    "status": "pass",
                    "message": f"Backend health check passed at {path}.",
                    "details": {"attempts": attempts},
                }

        return {
            "name": "backend.health",
            "status": "fail",
            "message": "No backend health endpoint returned a healthy response.",
            "details": {"attempts": attempts},
        }

    def _get_backend_health_path(self, path: str) -> dict[str, JsonValue]:
        try:
            request = urllib.request.Request(_java_backend_url(path), headers=_java_backend_headers(), method="GET")
            with urllib.request.urlopen(request, timeout=_java_request_timeout_seconds()) as response:
                body = response.read(4096)
                parsed = self._parse_json_body(body)
                healthy = response.status < 400 and isinstance(parsed, dict) and str(parsed.get("status", "")).upper() == "UP"
                return {
                    "path": path,
                    "statusCode": response.status,
                    "contentType": response.headers.get("Content-Type"),
                    "serverHeader": response.headers.get("Server"),
                    "healthy": healthy,
                    "body": parsed if parsed is not None else body.decode("utf-8", errors="replace")[:500],
                }
        except urllib.error.HTTPError as exc:
            return {
                "path": path,
                "statusCode": exc.code,
                "healthy": False,
                "body": exc.read().decode("utf-8", errors="replace")[:500],
            }
        except urllib.error.URLError as exc:
            return {"path": path, "healthy": False, "error": str(exc.reason)}
        except McpToolError as exc:
            return {"path": path, "healthy": False, "error": str(exc)}

    def _parse_json_body(self, body: bytes) -> JsonValue | None:
        try:
            return _normalize_json_value(json.loads(body.decode("utf-8")))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None

    def _check_backend_operation_probe(self, enabled: bool) -> dict[str, JsonValue]:
        if not enabled:
            return {
                "name": "backend.operationProbe",
                "status": "skip",
                "message": "Backend operation probe was disabled.",
            }
        fixture_pdf = _REPO_ROOT / "testing" / "test_pdf_1.pdf"
        if not fixture_pdf.exists():
            return {
                "name": "backend.operationProbe",
                "status": "skip",
                "message": "No repo fixture PDF is available for the rotate-pdf probe.",
                "details": {"fixturePath": str(fixture_pdf)},
            }
        try:
            executor = MultipartEndpointExecutor()
            body, boundary, body_size = executor._open_multipart_body(
                {"angle": 90},
                [("fileInput", fixture_pdf)],
            )
            try:
                request = urllib.request.Request(
                    _java_backend_url("/api/v1/general/rotate-pdf"),
                    data=body,
                    headers=executor._multipart_headers(boundary, body_size),
                    method="POST",
                )
                with urllib.request.urlopen(request, timeout=_java_request_timeout_seconds()) as response:
                    response_body = response.read(1024)
                    content_type = response.headers.get("Content-Type", "")
                    ok = response.status < 400 and (
                        "pdf" in content_type.lower() or response_body.startswith(b"%PDF")
                    )
                    return {
                        "name": "backend.operationProbe",
                        "status": "pass" if ok else "fail",
                        "message": "rotate-pdf probe returned a PDF."
                        if ok
                        else "rotate-pdf probe did not return a PDF.",
                        "details": {
                            "endpoint": "/api/v1/general/rotate-pdf",
                            "statusCode": response.status,
                            "contentType": content_type,
                            "bytesRead": len(response_body),
                        },
                    }
            finally:
                executor._close_multipart_body(body)
        except urllib.error.HTTPError as exc:
            return {
                "name": "backend.operationProbe",
                "status": "fail",
                "message": f"rotate-pdf probe failed with HTTP {exc.code}.",
                "details": {
                    "endpoint": "/api/v1/general/rotate-pdf",
                    "statusCode": exc.code,
                    "body": exc.read().decode("utf-8", errors="replace")[:500],
                },
            }
        except (urllib.error.URLError, McpToolError) as exc:
            return {
                "name": "backend.operationProbe",
                "status": "fail",
                "message": f"rotate-pdf probe failed: {exc}",
                "details": {"endpoint": "/api/v1/general/rotate-pdf"},
            }

    def _check_ai_provider(self, run_probe: bool) -> dict[str, JsonValue]:
        provider_configured = bool(_env_value("STIRLING_OPENAI_API_KEY") or _env_value("STIRLING_ANTHROPIC_API_KEY"))
        if not provider_configured:
            return {
                "name": "ai.provider",
                "status": "fail",
                "message": "No OpenAI or Anthropic API key is configured.",
                "details": {
                    "openaiConfigured": False,
                    "anthropicConfigured": False,
                    "liveProbe": False,
                },
            }
        if not run_probe:
            return {
                "name": "ai.provider",
                "status": "pass",
                "message": "AI provider credentials are present. Live provider probe was not requested.",
                "details": {
                    "openaiConfigured": bool(_env_value("STIRLING_OPENAI_API_KEY")),
                    "anthropicConfigured": bool(_env_value("STIRLING_ANTHROPIC_API_KEY")),
                    "liveProbe": False,
                },
            }

        try:
            from config import FAST_MODEL
            from llm_utils import run_ai

            response = run_ai(
                FAST_MODEL,
                [
                    models.ChatMessage(role="system", content="Return JSON matching the schema."),
                    models.ChatMessage(role="user", content="Set success to true."),
                ],
                models.SuccessResponse,
                max_tokens=50,
                tag="mcp_health_ai_probe",
                log_label="mcp-health-ai-probe",
            )
            return {
                "name": "ai.provider",
                "status": "pass" if response.success else "fail",
                "message": "Live AI provider probe succeeded." if response.success else "Live AI provider probe returned false.",
                "details": {"model": FAST_MODEL, "liveProbe": True},
            }
        except Exception as exc:
            return {
                "name": "ai.provider",
                "status": "fail",
                "message": f"Live AI provider probe failed: {exc}",
                "details": {"liveProbe": True},
            }


class StirlingMcpToolRegistry:
    SERVER_NAME = "stirling-pdf-engine-mcp"
    SERVER_VERSION = "0.1.0"
    PROTOCOL_VERSION = "2024-11-05"

    def __init__(
        self,
        tool_catalog: ToolCatalogService | None = None,
        metadata_resolver: FrontendOperationMetadataResolver | None = None,
        endpoint_executor: MultipartEndpointExecutor | None = None,
        health_checker: StirlingMcpHealthChecker | None = None,
    ) -> None:
        self._tool_catalog = tool_catalog
        self.metadata_resolver = metadata_resolver or FrontendOperationMetadataResolver()
        self.endpoint_executor = endpoint_executor or MultipartEndpointExecutor()
        self.health_checker = health_checker or StirlingMcpHealthChecker()
        self._tools = {
            "stirling_health_check": ToolDefinition(
                name="stirling_health_check",
                description="Check MCP, engine environment, filesystem access, backend reachability, PDF tooling, and AI provider readiness.",
                input_model=HealthCheckArgs,
            ),
            "stirling_list_operations": ToolDefinition(
                name="stirling_list_operations",
                description="List the Stirling PDF operations that the AI engine can plan and describe.",
                input_model=NoArgs,
            ),
            "stirling_get_operation_details": ToolDefinition(
                name="stirling_get_operation_details",
                description="Get JSON schema, frontend hook hints, and source references for a Stirling operation.",
                input_model=GetOperationDetailsArgs,
            ),
            "stirling_plan_edit_request": ToolDefinition(
                name="stirling_plan_edit_request",
                description="Turn a natural-language PDF editing request into Stirling operation ids and parameters.",
                input_model=PlanEditRequestArgs,
            ),
            "stirling_answer_pdf_question": ToolDefinition(
                name="stirling_answer_pdf_question",
                description="Answer a question about a local PDF file using the engine's PDF question workflow.",
                input_model=AnswerPdfQuestionArgs,
            ),
            "stirling_read_pdf_editor_document": ToolDefinition(
                name="stirling_read_pdf_editor_document",
                description="Convert a local PDF into the structured JSON format used by Stirling's PDF text editor.",
                input_model=ReadPdfEditorDocumentArgs,
            ),
            "stirling_call_endpoint": ToolDefinition(
                name="stirling_call_endpoint",
                description="Call a Stirling backend /api/v1/ endpoint with multipart form data and save the binary output.",
                input_model=CallEndpointArgs,
            ),
            "stirling_get_job_status": ToolDefinition(
                name="stirling_get_job_status",
                description="Check a Stirling async job and optionally fetch its completed result.",
                input_model=JobStatusArgs,
            ),
            "stirling_rotate_pdf": ToolDefinition(
                name="stirling_rotate_pdf",
                description="Rotate a local PDF through the Stirling backend and save the output.",
                input_model=RotatePdfArgs,
            ),
            "stirling_merge_pdfs": ToolDefinition(
                name="stirling_merge_pdfs",
                description="Merge local PDFs through the Stirling backend and save the output.",
                input_model=MergePdfsArgs,
            ),
            "stirling_compress_pdf": ToolDefinition(
                name="stirling_compress_pdf",
                description="Compress a local PDF through the Stirling backend and save the output.",
                input_model=CompressPdfArgs,
            ),
            "stirling_remove_pages": ToolDefinition(
                name="stirling_remove_pages",
                description="Remove pages from a local PDF through the Stirling backend and save the output.",
                input_model=RemovePagesArgs,
            ),
        }

    @property
    def tool_catalog(self) -> Any:
        return self._tool_catalog or StaticToolCatalogService()

    def _planning_tool_catalog(self) -> Any:
        if self._tool_catalog is None:
            from file_processing_agent import ToolCatalogService

            self._tool_catalog = ToolCatalogService()
        return self._tool_catalog

    def list_tools(self) -> list[dict[str, JsonValue]]:
        return [
            {
                "name": tool.name,
                "description": tool.description,
                "inputSchema": tool.input_model.model_json_schema(),
            }
            for tool in self._tools.values()
        ]

    def call_tool(self, name: str, arguments: dict[str, Any] | None) -> dict[str, JsonValue]:
        if name not in self._tools:
            raise McpToolError(f"Unknown MCP tool: {name}")
        payload = arguments or {}
        try:
            if name == "stirling_health_check":
                args = HealthCheckArgs.model_validate(payload)
                result = self.health_checker.run(args)
            elif name == "stirling_list_operations":
                NoArgs.model_validate(payload)
                result = self._list_operations()
            elif name == "stirling_get_operation_details":
                args = GetOperationDetailsArgs.model_validate(payload)
                result = self._get_operation_details(args.operation_id)
            elif name == "stirling_plan_edit_request":
                args = PlanEditRequestArgs.model_validate(payload)
                result = self._plan_edit_request(args)
            elif name == "stirling_answer_pdf_question":
                args = AnswerPdfQuestionArgs.model_validate(payload)
                result = self._answer_pdf_question(args)
            elif name == "stirling_read_pdf_editor_document":
                args = ReadPdfEditorDocumentArgs.model_validate(payload)
                result = self._read_pdf_editor_document(args)
            elif name == "stirling_call_endpoint":
                args = CallEndpointArgs.model_validate(payload)
                result = self._call_endpoint(args)
            elif name == "stirling_get_job_status":
                args = JobStatusArgs.model_validate(payload)
                result = self._get_job_status(args)
            elif name == "stirling_rotate_pdf":
                args = RotatePdfArgs.model_validate(payload)
                result = self._rotate_pdf(args)
            elif name == "stirling_merge_pdfs":
                args = MergePdfsArgs.model_validate(payload)
                result = self._merge_pdfs(args)
            elif name == "stirling_compress_pdf":
                args = CompressPdfArgs.model_validate(payload)
                result = self._compress_pdf(args)
            elif name == "stirling_remove_pages":
                args = RemovePagesArgs.model_validate(payload)
                result = self._remove_pages(args)
            else:
                raise McpToolError(f"Unhandled MCP tool: {name}")
        except ValidationError as exc:
            raise McpToolError(str(exc)) from exc
        return {
            "content": [
                {
                    "type": "text",
                    "text": json.dumps(result, ensure_ascii=True, indent=2),
                }
            ],
            "isError": False,
        }

    def list_resources(self) -> list[dict[str, JsonValue]]:
        return [
            {
                "uri": "stirling://operations/catalog",
                "name": "Stirling Operation Catalog",
                "mimeType": "application/json",
                "description": "Available operations plus their MCP-visible schemas and hook hints.",
            },
            {
                "uri": "stirling://mcp/readme",
                "name": "Stirling MCP Guide",
                "mimeType": "text/markdown",
                "description": "Local usage notes for running the Stirling PDF MCP server.",
            },
        ]

    def read_resource(self, uri: str) -> dict[str, JsonValue]:
        if uri == "stirling://operations/catalog":
            payload = json.dumps(self._list_operations(), ensure_ascii=True, indent=2)
            mime_type = "application/json"
        elif uri == "stirling://mcp/readme":
            payload = _MCP_README_PATH.read_text(encoding="utf-8") if _MCP_README_PATH.exists() else ""
            mime_type = "text/markdown"
        else:
            raise McpToolError(f"Unknown MCP resource: {uri}")
        return {
            "contents": [
                {
                    "uri": uri,
                    "mimeType": mime_type,
                    "text": payload,
                }
            ]
        }

    def _list_operations(self) -> dict[str, JsonValue]:
        operations: JsonArray = []
        for operation_id in self.tool_catalog.get_catalog().operation_ids:
            param_model = self.tool_catalog.get_operation(operation_id)
            metadata = self.metadata_resolver.get(str(operation_id))
            operations.append(
                {
                    "operationId": str(operation_id),
                    "inputSchema": _normalize_json_value(param_model.model_json_schema(by_alias=True))
                    if param_model
                    else None,
                    "frontendMetadata": metadata.to_dict() if metadata else None,
                }
            )
        return {"operations": operations}

    def _get_operation_details(self, operation_id: str) -> dict[str, JsonValue]:
        try:
            op_enum = models.tool_models.OperationId(operation_id)
        except ValueError as exc:
            raise McpToolError(f"Unknown operation id: {operation_id}") from exc
        param_model = self.tool_catalog.get_operation(op_enum)
        metadata = self.metadata_resolver.get(operation_id)
        return {
            "operationId": operation_id,
            "inputSchema": _normalize_json_value(param_model.model_json_schema(by_alias=True)) if param_model else None,
            "fieldDefaults": _normalize_json_value(param_model.model_validate({}).model_dump(by_alias=True, mode="json"))
            if param_model
            else {},
            "frontendMetadata": metadata.to_dict() if metadata else None,
        }

    def _plan_edit_request(self, args: PlanEditRequestArgs) -> dict[str, JsonValue]:
        uploaded_files = [self._uploaded_file_info(path) for path in args.file_paths]
        preflight = self._first_pdf_preflight(args.file_paths)
        history = list(args.history)
        history.append(models.ChatMessage(role="user", content=args.request))
        tool_catalog = self._planning_tool_catalog()

        selection = tool_catalog.select_edit_tool(
            history=history,
            uploaded_files=uploaded_files,
            preflight=preflight,
        )

        selected_ops: list[tuple[models.tool_models.OperationId, models.tool_models.ParamToolModel | None]] = []
        for operation_id in selection.operation_ids:
            params = tool_catalog.extract_operation_parameters(
                operation_id=operation_id,
                previous_operations=selected_ops,
                user_message=args.request,
                history=history,
                preflight=preflight,
            )
            selected_ops.append((operation_id, params))

        operation_ids = [operation_id for operation_id, _ in selected_ops]
        validation = _validate_operation_chain(operation_ids)
        risk = _assess_plan_risk(operation_ids, preflight)
        planned_operations: JsonArray = [
            {
                "operationId": str(operation_id),
                "parameters": _normalize_json_value(
                    parameters.model_dump(by_alias=True, exclude_none=True, mode="json")
                )
                if parameters
                else {},
            }
            for operation_id, parameters in selected_ops
        ]
        return {
            "request": args.request,
            "selectionAction": selection.action,
            "responseMessage": selection.response_message,
            "operations": planned_operations,
            "summary": _normalize_json_value(_build_plan_summary(operation_ids)),
            "preflight": _normalize_json_value(preflight.model_dump(by_alias=True, exclude_none=True, mode="json"))
            if preflight
            else None,
            "risk": _normalize_json_value(risk),
            "validation": {
                "isValid": validation.is_valid,
                "errorMessage": validation.error_message,
                "errorData": _normalize_json_value(
                    validation.error_data.model_dump(by_alias=True, exclude_none=True, mode="json")
                )
                if validation.error_data
                else None,
            },
        }

    def _answer_pdf_question(self, args: AnswerPdfQuestionArgs) -> dict[str, JsonValue]:
        pdf_path = str(self._resolve_path(args.pdf_path))
        return {
            "pdfPath": pdf_path,
            "question": args.question,
            "answer": _answer_pdf_question(pdf_path, args.question),
        }

    def _read_pdf_editor_document(self, args: ReadPdfEditorDocumentArgs) -> dict[str, JsonValue]:
        from pdf_text_editor import convert_pdf_to_text_editor_document

        pdf_path = str(self._resolve_path(args.pdf_path))
        document = convert_pdf_to_text_editor_document(pdf_path)
        return {
            "pdfPath": pdf_path,
            "document": _normalize_json_value(document.model_dump(by_alias=True, exclude_none=True, mode="json")),
        }

    def _call_endpoint(self, args: CallEndpointArgs) -> dict[str, JsonValue]:
        return self.endpoint_executor.call_endpoint(
            endpoint=args.endpoint,
            file_paths=args.file_paths,
            file_field_name=args.file_field_name,
            extra_file_fields=args.extra_file_fields,
            form_fields=args.form_fields,
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _get_job_status(self, args: JobStatusArgs) -> dict[str, JsonValue]:
        if args.fetch_result:
            return self.endpoint_executor.get_job_result(args.job_id, args.output_path)
        return self.endpoint_executor.get_job_status(args.job_id)

    def _rotate_pdf(self, args: RotatePdfArgs) -> dict[str, JsonValue]:
        if args.angle % 90 != 0:
            raise McpToolError("angle must be a multiple of 90.")
        return self.endpoint_executor.call_endpoint(
            endpoint="/api/v1/general/rotate-pdf",
            file_paths=[args.pdf_path],
            file_field_name="fileInput",
            extra_file_fields={},
            form_fields={"angle": args.angle},
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _merge_pdfs(self, args: MergePdfsArgs) -> dict[str, JsonValue]:
        return self.endpoint_executor.call_endpoint(
            endpoint="/api/v1/general/merge-pdfs",
            file_paths=args.pdf_paths,
            file_field_name="fileInput",
            extra_file_fields={},
            form_fields={
                "clientFileIds": json.dumps([Path(path).name for path in args.pdf_paths], ensure_ascii=True),
                "sortType": "orderProvided",
                "removeCertSign": args.remove_digital_signature,
                "generateToc": args.generate_table_of_contents,
            },
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _compress_pdf(self, args: CompressPdfArgs) -> dict[str, JsonValue]:
        compression_method = args.compression_method.strip()
        if compression_method not in {"quality", "fileSize"}:
            raise McpToolError("compression_method must be 'quality' or 'fileSize'.")
        form_fields: dict[str, JsonValue] = {
            "grayscale": args.grayscale,
            "lineArt": args.line_art,
            "linearize": args.linearize,
        }
        if compression_method == "quality":
            form_fields["optimizeLevel"] = args.compression_level
        elif args.expected_output_size:
            form_fields["expectedOutputSize"] = args.expected_output_size
        else:
            raise McpToolError("expected_output_size is required when compression_method is 'fileSize'.")
        if args.line_art:
            form_fields["lineArtThreshold"] = args.line_art_threshold
            form_fields["lineArtEdgeLevel"] = args.line_art_edge_level
        return self.endpoint_executor.call_endpoint(
            endpoint="/api/v1/misc/compress-pdf",
            file_paths=[args.pdf_path],
            file_field_name="fileInput",
            extra_file_fields={},
            form_fields=form_fields,
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _remove_pages(self, args: RemovePagesArgs) -> dict[str, JsonValue]:
        page_numbers = re.sub(r"\s+", "", args.page_numbers)
        if not page_numbers:
            raise McpToolError("page_numbers must not be empty.")
        return self.endpoint_executor.call_endpoint(
            endpoint="/api/v1/general/remove-pages",
            file_paths=[args.pdf_path],
            file_field_name="fileInput",
            extra_file_fields={},
            form_fields={"pageNumbers": page_numbers},
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _uploaded_file_info(self, file_path: str) -> models.UploadedFileInfo:
        path = self._resolve_path(file_path)
        mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        return models.UploadedFileInfo(name=path.name, type=mime_type)

    def _first_pdf_preflight(self, file_paths: list[str]) -> models.PdfPreflight | None:
        for file_path in file_paths:
            path = self._resolve_path(file_path)
            mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
            if mime_type == "application/pdf" or path.suffix.lower() == ".pdf":
                return _get_pdf_preflight(str(path))
        return None

    def _resolve_path(self, file_path: str) -> Path:
        path = Path(file_path).expanduser()
        if not path.is_absolute():
            path = Path.cwd() / path
        path = path.resolve()
        if not path.exists():
            raise McpToolError(f"File not found: {path}")
        return path
