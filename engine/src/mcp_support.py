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
from ast import literal_eval
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

import models

if TYPE_CHECKING:
    from file_processing_agent import ToolCatalogService

type JsonPrimitive = str | int | float | bool | None
type JsonObject = dict[str, "JsonValue"]
type JsonArray = list["JsonValue"]
type JsonValue = JsonPrimitive | JsonObject | JsonArray

_MCP_README_PATH = Path(__file__).resolve().parents[1] / "MCP.md"
_REPO_ROOT = Path(__file__).resolve().parents[2]
_ENGINE_ROOT = Path(__file__).resolve().parents[1]
_DEFAULT_OUTPUT_DIR = _ENGINE_ROOT / "output"
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


class McpProtocolError(RuntimeError):
    def __init__(self, code: int, message: str, data: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data


class McpArgsModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


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


class NoArgs(McpArgsModel):
    pass


class CleanupMcpOutputArgs(McpArgsModel):
    max_age_hours: float = Field(
        default=24.0,
        ge=0.0,
        description="Delete MCP output/temp files older than this age. Use 0 to delete all files.",
    )
    include_outputs: bool = Field(default=False, description="Also delete files in engine/output/mcp, not only tmp.")
    dry_run: bool = Field(default=True, description="Report files that would be deleted without deleting them.")


class HealthCheckArgs(McpArgsModel):
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


class GetOperationDetailsArgs(McpArgsModel):
    operation_id: str = Field(description="Stirling operation id, for example 'rotate' or 'merge'.")


class OperationCoverageArgs(McpArgsModel):
    include_frontend_metadata: bool = Field(
        default=False,
        description="Include frontend hook metadata for each operation. This can make the response large.",
    )


class PlanEditRequestArgs(McpArgsModel):
    request: str = Field(description="Natural-language PDF edit request.")
    file_paths: list[str] = Field(
        default_factory=list,
        description="Optional local file paths that provide file context for planning.",
    )
    history: list[models.ChatMessage] = Field(
        default_factory=list,
        description="Optional prior conversation history in the same format the engine uses.",
    )


class SetupDiagnosticsArgs(McpArgsModel):
    pass


class DiscoverPdfsArgs(McpArgsModel):
    root_paths: list[str] = Field(
        default_factory=list,
        description="Allowed directories to search. Defaults to the configured MCP allowed roots.",
    )
    recursive: bool = Field(default=True, description="Search child directories.")
    name_contains: str = Field(default="", description="Optional case-insensitive filename filter.")
    include_preflight: bool = Field(default=False, description="Include PDF preflight for each matched PDF.")
    max_results: int = Field(default=50, ge=1, le=500, description="Maximum PDFs to return.")


class ExecutePlanOperation(McpArgsModel):
    operation_id: str = Field(description="Operation id from stirling_plan_edit_request.")
    parameters: dict[str, JsonValue] = Field(
        default_factory=dict,
        description="Operation parameters. These are sent as multipart form fields.",
    )
    endpoint: str | None = Field(
        default=None,
        description="Optional static backend endpoint override for operations without a resolvable endpoint.",
    )
    file_field_name: str = Field(default="fileInput", description="Primary multipart file field name.")
    extra_file_fields: dict[str, str | list[str]] = Field(
        default_factory=dict,
        description="Additional multipart file fields for this step.",
    )


class ExecutePlanArgs(McpArgsModel):
    file_paths: list[str] = Field(
        min_length=1,
        description="Initial local files for the first plan step. Later PDF steps use the prior saved PDF output.",
    )
    operations: list[ExecutePlanOperation] = Field(min_length=1, description="Operations from a validated plan.")
    confirmed: bool = Field(
        default=False,
        description="Must be true when the chain risk assessment says the plan should be confirmed.",
    )
    output_path: str | None = Field(
        default=None,
        description="Optional destination for the final binary response. Intermediate outputs use engine/output/mcp.",
    )


class AnswerPdfQuestionArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or relative path to a local PDF file.")
    question: str = Field(description="Question to answer from the PDF text.")


class ReadPdfEditorDocumentArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or relative path to a local PDF file.")


class CallEndpointArgs(McpArgsModel):
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
        description="Optional destination path for binary responses. Defaults to engine/output/mcp/.",
    )
    async_job: bool = Field(
        default=False,
        description="Submit AutoJob endpoints with ?async=true and return the job id/status response.",
    )
    wait_for_job: bool = Field(
        default=False,
        description="When async_job is true, poll until the job completes and fetch the final result.",
    )
    confirmed: bool = Field(
        default=False,
        description="Required for high-risk security/signing/redaction endpoints.",
    )
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ExecuteOperationArgs(McpArgsModel):
    operation_id: str = Field(description="Stirling operation id, for example 'crop' or 'removeBlanks'.")
    file_paths: list[str] = Field(
        default_factory=list,
        description="Primary local files to attach under file_field_name.",
    )
    file_field_name: str = Field(default="fileInput", description="Multipart field name used for the primary files.")
    extra_file_fields: dict[str, str | list[str]] = Field(
        default_factory=dict,
        description="Additional multipart file fields required by the operation.",
    )
    form_fields: dict[str, JsonValue] = Field(
        default_factory=dict,
        description="Non-file multipart fields for the backend operation.",
    )
    endpoint: str | None = Field(
        default=None,
        description="Optional endpoint override for dynamic operations. Must start with /api/v1/.",
    )
    output_path: str | None = Field(default=None, description="Optional destination path for binary responses.")
    async_job: bool = False
    wait_for_job: bool = False
    confirmed: bool = Field(default=False, description="Required for high-risk security/signing/redaction endpoints.")
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class JobStatusArgs(McpArgsModel):
    job_id: str = Field(description="Stirling backend job id.")
    fetch_result: bool = Field(default=False, description="Fetch the final result if the job is complete.")
    output_path: str | None = Field(default=None, description="Optional destination path for binary job results.")


class RotatePdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    angle: int = Field(default=90, description="Rotation angle. Must be a multiple of 90.")
    output_path: str | None = Field(default=None, description="Optional destination path for the rotated PDF.")
    async_job: bool = Field(default=False, description="Submit as a backend async job.")
    wait_for_job: bool = Field(default=False, description="Poll and fetch the result when async_job is true.")
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class MergePdfsArgs(McpArgsModel):
    pdf_paths: list[str] = Field(min_length=2, description="PDF files to merge in the provided order.")
    remove_digital_signature: bool = Field(default=False, description="Remove certificate signatures before merge.")
    generate_table_of_contents: bool = Field(
        default=False, description="Generate a table of contents in the merged PDF."
    )
    output_path: str | None = Field(default=None, description="Optional destination path for the merged PDF.")
    async_job: bool = Field(default=False, description="Submit as a backend async job.")
    wait_for_job: bool = Field(default=False, description="Poll and fetch the result when async_job is true.")
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class CompressPdfArgs(McpArgsModel):
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


class RemovePagesArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    page_numbers: str = Field(description="Pages or ranges to remove, for example 1,3,5-7.")
    output_path: str | None = Field(default=None, description="Optional destination path for the result PDF.")
    async_job: bool = Field(default=False, description="Submit as a backend async job.")
    wait_for_job: bool = Field(default=False, description="Poll and fetch the result when async_job is true.")
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class SplitPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    page_numbers: str = Field(description="Pages/ranges to split at, for example 1,3,5-7.")
    output_path: str | None = Field(default=None, description="Optional destination path for the result ZIP/PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ExtractImagesArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    image_format: str = Field(default="png", description="Image format, for example png, jpg, or tiff.")
    output_path: str | None = Field(default=None, description="Optional destination path for the result ZIP.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class OcrPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    languages: list[str] = Field(default_factory=lambda: ["eng"], description="OCR languages.")
    ocr_type: str = Field(default="skip-text", description="OCR type sent to backend.")
    ocr_render_type: str = Field(default="hocr", description="OCR render type sent to backend.")
    sidecar: bool = False
    deskew: bool = False
    clean: bool = False
    clean_final: bool = False
    remove_images_after: bool = False
    output_path: str | None = Field(default=None, description="Optional destination path for the OCR result.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ConvertFileArgs(McpArgsModel):
    file_paths: list[str] = Field(min_length=1, description="Local files to convert.")
    from_extension: str = Field(description="Source extension/type, for example pdf, docx, image, html.")
    to_extension: str = Field(description="Target extension/type, for example pdf, png, docx, txt.")
    output_path: str | None = Field(default=None, description="Optional destination path for the converted result.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class WatermarkPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    watermark_text: str = Field(description="Text watermark to add.")
    font_size: int = 30
    rotation: int = 45
    opacity_percent: int = Field(default=50, ge=0, le=100)
    width_spacer: int = 50
    height_spacer: int = 50
    output_path: str | None = Field(default=None, description="Optional destination path for the watermarked PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class AddPasswordArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    password: str = Field(description="User password.")
    owner_password: str = Field(default="", description="Owner password. Defaults to password when empty.")
    key_length: int = Field(default=256, description="Encryption key length.")
    output_path: str | None = Field(default=None, description="Optional destination path for encrypted PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class RemovePasswordArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    password: str = Field(description="Current PDF password.")
    output_path: str | None = Field(default=None, description="Optional destination path for decrypted PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class RepairPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    output_path: str | None = Field(default=None, description="Optional destination path for repaired PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class SanitizePdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    remove_javascript: bool = True
    remove_embedded_files: bool = True
    remove_xmp_metadata: bool = True
    remove_metadata: bool = True
    remove_links: bool = False
    remove_fonts: bool = False
    output_path: str | None = Field(default=None, description="Optional destination path for sanitized PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class FlattenPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    flatten_only_forms: bool = False
    render_dpi: int | None = Field(default=None, description="Optional render DPI.")
    output_path: str | None = Field(default=None, description="Optional destination path for flattened PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ExtractPagesPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    page_numbers: str = Field(description="Backend-ready one-based page numbers/ranges to keep.")
    output_path: str | None = Field(default=None, description="Optional destination path for extracted pages PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class CropPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    auto_crop: bool = Field(default=False, description="Let the backend determine the crop area.")
    x: float | None = Field(default=None, description="Crop x coordinate when auto_crop is false.")
    y: float | None = Field(default=None, description="Crop y coordinate when auto_crop is false.")
    width: float | None = Field(default=None, description="Crop width when auto_crop is false.")
    height: float | None = Field(default=None, description="Crop height when auto_crop is false.")
    output_path: str | None = Field(default=None, description="Optional destination path for cropped PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ScalePagesPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    scale_factor: float = Field(default=1.0, gt=0, description="Page content scale factor.")
    page_size: str = Field(default="KEEP", description="Target page size, for example KEEP, A4, LETTER.")
    output_path: str | None = Field(default=None, description="Optional destination path for scaled PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class RedactPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    words_to_redact: list[str] = Field(min_length=1, description="Text lines or patterns to redact.")
    confirmed: bool = Field(default=False, description="Must be true because automatic redaction is destructive.")
    use_regex: bool = False
    whole_word_search: bool = False
    redact_color: str = Field(default="#000000", description="Redaction fill color, with or without leading #.")
    custom_padding: float = Field(default=0.1, ge=0)
    convert_pdf_to_image: bool = True
    output_path: str | None = Field(default=None, description="Optional destination path for redacted PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ReorganizePagesPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    page_numbers: str | None = Field(default=None, description="Page order sent to rearrange-pages.")
    custom_mode: str | None = Field(default=None, description="Optional backend custom rearrange mode.")
    output_path: str | None = Field(default=None, description="Optional destination path for reorganized PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class OverlayPdfsArgs(McpArgsModel):
    pdf_path: str = Field(description="Base PDF to receive overlays.")
    overlay_pdf_paths: list[str] = Field(min_length=1, description="Overlay PDF files.")
    overlay_mode: str = Field(default="SequentialOverlay")
    overlay_position: int = Field(default=0, description="Backend overlay position value.")
    counts: list[int] = Field(default_factory=list, description="Repeat counts for FixedRepeatOverlay mode.")
    output_path: str | None = Field(default=None, description="Optional destination path for overlaid PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class PageLayoutPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    pages_per_sheet: int = Field(default=4, ge=1)
    add_border: bool = False
    output_path: str | None = Field(default=None, description="Optional destination path for page layout PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class BookletPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    pages_per_sheet: int = Field(default=2, ge=1)
    add_border: bool = False
    spine_location: str = "LEFT"
    add_gutter: bool = False
    gutter_size: float = Field(default=12, ge=0)
    double_sided: bool = True
    duplex_pass: str = "BOTH"
    flip_on_short_edge: bool = False
    output_path: str | None = Field(default=None, description="Optional destination path for booklet PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class SignaturePositionArgs(McpArgsModel):
    x: float = Field(ge=0)
    y: float = Field(ge=0)
    width: float = Field(gt=0)
    height: float = Field(gt=0)
    page: int = Field(ge=0, description="Zero-based page index used by the add-signature endpoint.")


class SignPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    signature_type: str = Field(default="text", description="text, image, or canvas.")
    signature_data: str | None = Field(default=None, description="Base64/data payload for image or canvas signatures.")
    signature_position: SignaturePositionArgs | None = None
    reason: str | None = None
    location: str | None = None
    signer_name: str | None = Field(default=None, description="Required for text signatures.")
    confirmed: bool = Field(default=False, description="Must be true because signing changes the document.")
    output_path: str | None = Field(default=None, description="Optional destination path for signed PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class CertSignPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    sign_mode: str = Field(
        default="MANUAL",
        description="MANUAL, AUTO server-certificate, or KMS signing.",
        json_schema_extra={"enum": ["MANUAL", "AUTO", "KMS"]},
    )
    cert_type: str = Field(
        default="",
        description="PEM, PKCS12, PFX, or JKS for manual signing.",
        json_schema_extra={"enum": ["", "PEM", "PKCS12", "PFX", "JKS"]},
    )
    password: str = ""
    private_key_path: str | None = Field(default=None, description="PEM private key path.")
    cert_path: str | None = Field(
        default=None,
        description="PEM certificate path, or signer certificate chain path for KMS signing.",
    )
    p12_path: str | None = Field(default=None, description="PKCS12/PFX keystore path.")
    jks_path: str | None = Field(default=None, description="JKS keystore path.")
    kms_cert_path: str | None = Field(default=None, description="Signer certificate chain path for KMS signing.")
    kms_key_id: str = Field(default="", description="Optional configured KMS key alias or identifier.")
    kms_signature_algorithm: str = Field(
        default="SHA256_WITH_RSA",
        description="SHA256_WITH_RSA or SHA256_WITH_ECDSA.",
        json_schema_extra={"enum": ["SHA256_WITH_RSA", "SHA256_WITH_ECDSA"]},
    )
    show_signature: bool = False
    reason: str = ""
    location: str = ""
    name: str = ""
    page_number: int = Field(default=1, ge=1, description="One-based visible signature page number.")
    show_logo: bool = True
    confirmed: bool = Field(default=False, description="Must be true because certificate signing changes the document.")
    output_path: str | None = Field(default=None, description="Optional destination path for certificate-signed PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class MetadataEntryArgs(McpArgsModel):
    key: str
    value: str


class ChangeMetadataPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    title: str = ""
    author: str = ""
    subject: str = ""
    keywords: str = ""
    creator: str = ""
    producer: str = ""
    creation_date: str = Field(default="", description="Backend metadata date string, YYYY/MM/DD HH:MM:SS.")
    modification_date: str = Field(default="", description="Backend metadata date string, YYYY/MM/DD HH:MM:SS.")
    trapped: str = ""
    delete_all: bool = False
    custom_metadata: list[MetadataEntryArgs] = Field(default_factory=list)
    output_path: str | None = Field(default=None, description="Optional destination path for metadata PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ChangePermissionsPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    password: str = Field(default="", description="Optional user password for the output PDF.")
    owner_password: str = Field(default="", description="Optional owner password for the output PDF.")
    key_length: int = Field(default=256)
    prevent_assembly: bool = False
    prevent_extract_content: bool = False
    prevent_extract_for_accessibility: bool = False
    prevent_fill_in_form: bool = False
    prevent_modify: bool = False
    prevent_modify_annotations: bool = False
    prevent_printing: bool = False
    prevent_printing_faithful: bool = False
    confirmed: bool = Field(default=False, description="Must be true because permissions/security are changed.")
    output_path: str | None = Field(default=None, description="Optional destination path for permissions PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ConfirmedPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    confirmed: bool = Field(default=False, description="Must be true because this operation changes the document.")
    output_path: str | None = Field(default=None, description="Optional destination path for output PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class AddAttachmentsPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    attachment_paths: list[str] = Field(min_length=1, description="Local files to embed into the PDF.")
    convert_to_pdfa3b: bool = Field(default=False, description="Convert the result to PDF/A-3b before embedding.")
    output_path: str | None = Field(default=None, description="Optional destination path for attached PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class BookmarkArgs(McpArgsModel):
    title: str = Field(min_length=1)
    page_number: int = Field(default=1, ge=1)
    children: list[BookmarkArgs] = Field(default_factory=list)


class EditTableOfContentsPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    bookmarks: list[BookmarkArgs] = Field(min_length=1, description="Bookmarks/table-of-contents entries to apply.")
    replace_existing: bool = Field(
        default=True, description="Kept for frontend parity; backend currently replaces outline."
    )
    output_path: str | None = Field(default=None, description="Optional destination path for TOC-updated PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class RemoveBlanksPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    threshold: int = Field(default=10, ge=0, le=255, description="Pixel whiteness threshold.")
    white_percent: float = Field(
        default=99.9, ge=0, le=100, description="Percentage of white pixels to treat as blank."
    )
    output_path: str | None = Field(default=None, description="Optional destination path for the result ZIP.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ScannerImageSplitPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF/image.")
    angle_threshold: float = 10
    tolerance: float = 30
    min_area: float = 10000
    min_contour_area: float = 500
    border_size: float = 1
    output_path: str | None = Field(default=None, description="Optional destination path for the result image/ZIP.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


class ReplaceColorPdfArgs(McpArgsModel):
    pdf_path: str = Field(description="Absolute or workspace-relative path to a local PDF.")
    replace_and_invert_option: str = Field(
        default="HIGH_CONTRAST_COLOR",
        description="HIGH_CONTRAST_COLOR, CUSTOM_COLOR, FULL_INVERSION, or COLOR_SPACE_CONVERSION.",
    )
    high_contrast_color_combination: str = Field(default="WHITE_TEXT_ON_BLACK")
    text_color: str = Field(default="#000000")
    background_color: str = Field(default="#ffffff")
    output_path: str | None = Field(default=None, description="Optional destination path for color-processed PDF.")
    async_job: bool = False
    wait_for_job: bool = False
    poll_interval_seconds: float = Field(default=1.0, ge=0.1, le=30.0)
    poll_timeout_seconds: float = Field(default=120.0, ge=1.0, le=3600.0)


BookmarkArgs.model_rebuild()


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
                        f'--{boundary}\r\nContent-Disposition: form-data; name="{field_name}"\r\n\r\n{normalized}\r\n'
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

        request_id = f"mcp-{uuid.uuid4().hex}"
        body, boundary, body_size = self._open_multipart_body(form_fields, primary_files + additional_files)
        request_endpoint = f"{endpoint}?async=true" if async_job else endpoint
        headers = self._multipart_headers(boundary, body_size, request_id)
        try:
            request = urllib.request.Request(
                _java_backend_url(request_endpoint),
                data=body,
                headers=headers,
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=_java_request_timeout_seconds()) as response:
                result = self._handle_response(response, endpoint, output_path, request_id)
            if async_job and wait_for_job:
                job_id = self._extract_job_id(result)
                if not job_id:
                    raise McpToolError("Async backend response did not include a job id.")
                return self.wait_for_job(job_id, output_path, poll_interval_seconds, poll_timeout_seconds)
            return result
        except urllib.error.HTTPError as exc:
            raise self._backend_http_error(exc, endpoint, request_id) from exc
        except urllib.error.URLError as exc:
            raise self._backend_url_error(exc) from exc
        finally:
            self._close_multipart_body(body)

    def get_job_status(self, job_id: str) -> dict[str, JsonValue]:
        response = self._get_json(f"/api/v1/general/job/{urllib.parse.quote(job_id)}")
        return {"jobId": job_id, "status": response}

    def get_job_result(self, job_id: str, output_path: str | None) -> dict[str, JsonValue]:
        endpoint = f"/api/v1/general/job/{urllib.parse.quote(job_id)}/result"
        request_id = f"mcp-{uuid.uuid4().hex}"
        headers = _java_backend_headers()
        headers["X-Stirling-MCP-Request-ID"] = request_id
        request = urllib.request.Request(_java_backend_url(endpoint), headers=headers, method="GET")
        try:
            with urllib.request.urlopen(request, timeout=_java_request_timeout_seconds()) as response:
                return self._handle_response(response, endpoint, output_path, request_id)
        except urllib.error.HTTPError as exc:
            raise self._backend_http_error(exc, endpoint, request_id) from exc
        except urllib.error.URLError as exc:
            raise self._backend_url_error(exc) from exc

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
        request_id = f"mcp-{uuid.uuid4().hex}"
        headers = _java_backend_headers()
        headers["X-Stirling-MCP-Request-ID"] = request_id
        request = urllib.request.Request(_java_backend_url(endpoint), headers=headers, method="GET")
        try:
            with urllib.request.urlopen(request, timeout=_java_request_timeout_seconds()) as response:
                raw = self._read_limited_json_response(response)
                return _normalize_json_value(json.loads(raw.decode("utf-8"))) if raw else {}
        except urllib.error.HTTPError as exc:
            raise self._backend_http_error(exc, endpoint, request_id) from exc
        except urllib.error.URLError as exc:
            raise self._backend_url_error(exc) from exc

    def _handle_response(
        self, response: Any, endpoint: str, output_path: str | None, request_id: str
    ) -> dict[str, JsonValue]:
        content_type = response.headers.get("Content-Type", "application/octet-stream")
        if "application/json" in content_type:
            raw = self._read_limited_json_response(response)
            text = raw.decode("utf-8") if raw else ""
            parsed = json.loads(text) if text else {}
            return {
                "endpoint": endpoint,
                "requestId": request_id,
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
            "requestId": request_id,
            "contentType": content_type,
            "savedPath": str(destination),
            "sizeBytes": size_bytes,
        }

    def _backend_http_error(self, exc: urllib.error.HTTPError, endpoint: str, request_id: str) -> McpToolError:
        detail = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
        parsed = self._parse_backend_error_detail(detail)
        message = parsed.get("message") or parsed.get("error") or detail or str(exc.reason)
        suggestion = self._backend_http_suggestion(exc.code, endpoint, message)
        return McpToolError(
            f"Backend HTTP {exc.code} for {endpoint} (requestId={request_id}): {message}. Suggested fix: {suggestion}"
        )

    def _parse_backend_error_detail(self, detail: str) -> dict[str, str]:
        if not detail:
            return {}
        try:
            parsed = json.loads(detail)
        except json.JSONDecodeError:
            return {"message": detail.strip()}
        if not isinstance(parsed, dict):
            return {"message": detail.strip()}
        result: dict[str, str] = {}
        for key in ("error", "message", "path"):
            value = parsed.get(key)
            if isinstance(value, str):
                result[key] = value
        return result

    def _backend_http_suggestion(self, status: int, endpoint: str, message: str) -> str:
        message_lower = message.casefold()
        if status in {401, 403}:
            if "disabled" in message_lower:
                return "Enable this endpoint in Stirling's endpoint configuration or choose a supported tool."
            return "Check STIRLING_JAVA_BACKEND_API_KEY and backend authentication/authorization settings."
        if status == 404:
            return "Verify the Java backend version exposes this endpoint and that STIRLING_JAVA_BACKEND_URL points to Stirling PDF."
        if status == 413:
            return "Use async mode, reduce input size, or raise the backend upload limit."
        if status == 415:
            return "Check file type and multipart field names for this endpoint."
        if status == 429:
            return "Retry later or reduce concurrent MCP/backend requests."
        if status >= 500:
            if any(
                token in message_lower for token in ("not installed", "python", "opencv", "ghostscript", "libreoffice")
            ):
                return "Install the backend dependency required by this endpoint and rerun stirling_health_check."
            return "Inspect the Stirling Java backend logs for this request and rerun the MCP live contract test."
        return "Check endpoint inputs, backend logs, and stirling_operation_coverage for the supported contract."

    def _backend_url_error(self, exc: urllib.error.URLError) -> McpToolError:
        backend_url = _env_value("STIRLING_JAVA_BACKEND_URL") or "<unset>"
        return McpToolError(
            f"Failed to reach Java backend at {backend_url}: {exc.reason}. "
            "Suggested fix: start Stirling on the configured port, verify STIRLING_JAVA_BACKEND_URL, "
            "then run stirling_health_check."
        )

    def _read_limited_json_response(self, response: Any) -> bytes:
        max_bytes = self._max_json_response_bytes()
        content_length = response.headers.get("Content-Length")
        if content_length:
            try:
                if int(content_length) > max_bytes:
                    raise McpToolError(
                        f"JSON response is larger than STIRLING_MCP_MAX_JSON_RESPONSE_BYTES ({max_bytes})."
                    )
            except ValueError:
                pass
        raw = response.read(max_bytes + 1)
        if len(raw) > max_bytes:
            raise McpToolError(f"JSON response is larger than STIRLING_MCP_MAX_JSON_RESPONSE_BYTES ({max_bytes}).")
        return raw

    def _max_json_response_bytes(self) -> int:
        raw = _env_value("STIRLING_MCP_MAX_JSON_RESPONSE_BYTES") or str(10 * 1024 * 1024)
        try:
            value = int(raw)
        except ValueError as exc:
            raise McpToolError("STIRLING_MCP_MAX_JSON_RESPONSE_BYTES must be an integer.") from exc
        return max(value, 1024)

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

    def _open_multipart_body(
        self, form_fields: dict[str, JsonValue], files: list[tuple[str, Path]]
    ) -> tuple[Any, str, int | None]:
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

    def _multipart_headers(self, boundary: str, body_size: int | None, request_id: str) -> dict[str, str]:
        headers = _java_backend_headers()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        headers["X-Stirling-MCP-Request-ID"] = request_id
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
        configured = [
            item.strip() for item in _env_value("STIRLING_MCP_ALLOWED_ROOTS").split(os.pathsep) if item.strip()
        ]
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
            "details": _normalize_json_value(
                {
                    "missingKeys": missing,
                    "emptyRequiredKeys": empty_required,
                    "openaiConfigured": openai_configured,
                    "anthropicConfigured": anthropic_configured,
                    "posthogConfigured": bool(_env_value("STIRLING_POSTHOG_API_KEY")),
                    "javaBackendConfigured": bool(_env_value("STIRLING_JAVA_BACKEND_URL")),
                }
            ),
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
                "disk": _normalize_json_value(self._disk_details(executor.output_dir)),
            },
        }

    def _disk_details(self, path: Path) -> dict[str, JsonValue]:
        usage = shutil.disk_usage(path)
        return {
            "totalBytes": usage.total,
            "usedBytes": usage.used,
            "freeBytes": usage.free,
            "freePercent": round((usage.free / usage.total) * 100, 2) if usage.total else None,
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
                healthy = (
                    response.status < 400 and isinstance(parsed, dict) and str(parsed.get("status", "")).upper() == "UP"
                )
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
                request_id = f"mcp-{uuid.uuid4().hex}"
                request = urllib.request.Request(
                    _java_backend_url("/api/v1/general/rotate-pdf"),
                    data=body,
                    headers=executor._multipart_headers(boundary, body_size, request_id),
                    method="POST",
                )
                with urllib.request.urlopen(request, timeout=_java_request_timeout_seconds()) as response:
                    response_body = response.read(1024)
                    content_type = response.headers.get("Content-Type", "")
                    ok = response.status < 400 and ("pdf" in content_type.lower() or response_body.startswith(b"%PDF"))
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
                            "requestId": request_id,
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
                "message": "Live AI provider probe succeeded."
                if response.success
                else "Live AI provider probe returned false.",
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
    PROTOCOL_VERSION = "2025-11-25"

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
            "stirling_setup_diagnostics": ToolDefinition(
                name="stirling_setup_diagnostics",
                description="Report MCP client setup diagnostics and desktop-client config hints.",
                input_model=SetupDiagnosticsArgs,
            ),
            "stirling_discover_pdfs": ToolDefinition(
                name="stirling_discover_pdfs",
                description="List PDFs under allowed MCP roots and optionally include PDF preflight metadata.",
                input_model=DiscoverPdfsArgs,
            ),
            "stirling_list_operations": ToolDefinition(
                name="stirling_list_operations",
                description="List the Stirling PDF operations that the AI engine can plan and describe.",
                input_model=NoArgs,
            ),
            "stirling_list_executable_operations": ToolDefinition(
                name="stirling_list_executable_operations",
                description="List operations with first-class executable MCP wrappers and their backend endpoints.",
                input_model=NoArgs,
            ),
            "stirling_operation_coverage": ToolDefinition(
                name="stirling_operation_coverage",
                description="Classify every known operation by MCP execution coverage and identify gaps.",
                input_model=OperationCoverageArgs,
            ),
            "stirling_cleanup_mcp_output": ToolDefinition(
                name="stirling_cleanup_mcp_output",
                description="Delete old MCP temp/output files using a retention policy.",
                input_model=CleanupMcpOutputArgs,
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
            "stirling_execute_plan": ToolDefinition(
                name="stirling_execute_plan",
                description="Execute a validated static-backend Stirling operation plan step by step.",
                input_model=ExecutePlanArgs,
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
            "stirling_execute_operation": ToolDefinition(
                name="stirling_execute_operation",
                description="Execute a backend-backed Stirling operation by operation id using multipart form data.",
                input_model=ExecuteOperationArgs,
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
            "stirling_split_pdf": ToolDefinition(
                name="stirling_split_pdf",
                description="Split a local PDF by pages/ranges through the Stirling backend.",
                input_model=SplitPdfArgs,
            ),
            "stirling_extract_images": ToolDefinition(
                name="stirling_extract_images",
                description="Extract images from a local PDF through the Stirling backend.",
                input_model=ExtractImagesArgs,
            ),
            "stirling_ocr_pdf": ToolDefinition(
                name="stirling_ocr_pdf",
                description="Run OCR on a local PDF through the Stirling backend.",
                input_model=OcrPdfArgs,
            ),
            "stirling_convert_file": ToolDefinition(
                name="stirling_convert_file",
                description="Convert files through a supported Stirling conversion endpoint.",
                input_model=ConvertFileArgs,
            ),
            "stirling_add_watermark": ToolDefinition(
                name="stirling_add_watermark",
                description="Add a text watermark to a local PDF.",
                input_model=WatermarkPdfArgs,
            ),
            "stirling_add_password": ToolDefinition(
                name="stirling_add_password",
                description="Encrypt a local PDF with a password.",
                input_model=AddPasswordArgs,
            ),
            "stirling_remove_password": ToolDefinition(
                name="stirling_remove_password",
                description="Remove a password from a local PDF.",
                input_model=RemovePasswordArgs,
            ),
            "stirling_repair_pdf": ToolDefinition(
                name="stirling_repair_pdf",
                description="Repair a local PDF through the Stirling backend.",
                input_model=RepairPdfArgs,
            ),
            "stirling_sanitize_pdf": ToolDefinition(
                name="stirling_sanitize_pdf",
                description="Sanitize a local PDF by removing active or sensitive content.",
                input_model=SanitizePdfArgs,
            ),
            "stirling_flatten_pdf": ToolDefinition(
                name="stirling_flatten_pdf",
                description="Flatten a local PDF through the Stirling backend.",
                input_model=FlattenPdfArgs,
            ),
            "stirling_extract_pages": ToolDefinition(
                name="stirling_extract_pages",
                description="Extract selected pages into a PDF through the Stirling backend.",
                input_model=ExtractPagesPdfArgs,
            ),
            "stirling_crop_pdf": ToolDefinition(
                name="stirling_crop_pdf",
                description="Crop a local PDF through the Stirling backend.",
                input_model=CropPdfArgs,
            ),
            "stirling_scale_pages": ToolDefinition(
                name="stirling_scale_pages",
                description="Scale PDF page content and page size through the Stirling backend.",
                input_model=ScalePagesPdfArgs,
            ),
            "stirling_redact_pdf": ToolDefinition(
                name="stirling_redact_pdf",
                description="Automatically redact matching text in a local PDF after explicit confirmation.",
                input_model=RedactPdfArgs,
            ),
            "stirling_reorganize_pages": ToolDefinition(
                name="stirling_reorganize_pages",
                description="Rearrange pages in a local PDF through the Stirling backend.",
                input_model=ReorganizePagesPdfArgs,
            ),
            "stirling_overlay_pdfs": ToolDefinition(
                name="stirling_overlay_pdfs",
                description="Overlay one or more PDFs onto a base PDF.",
                input_model=OverlayPdfsArgs,
            ),
            "stirling_page_layout": ToolDefinition(
                name="stirling_page_layout",
                description="Place multiple PDF pages onto each output sheet.",
                input_model=PageLayoutPdfArgs,
            ),
            "stirling_booklet_pdf": ToolDefinition(
                name="stirling_booklet_pdf",
                description="Create a booklet imposition PDF through the Stirling backend.",
                input_model=BookletPdfArgs,
            ),
            "stirling_sign_pdf": ToolDefinition(
                name="stirling_sign_pdf",
                description="Add a visual PDF signature after explicit confirmation.",
                input_model=SignPdfArgs,
            ),
            "stirling_cert_sign_pdf": ToolDefinition(
                name="stirling_cert_sign_pdf",
                description=(
                    "Digitally sign a PDF with certificate material, a server certificate, "
                    "or a configured KMS/HSM signer bridge after explicit confirmation."
                ),
                input_model=CertSignPdfArgs,
            ),
            "stirling_change_metadata": ToolDefinition(
                name="stirling_change_metadata",
                description="Update PDF metadata through the Stirling backend.",
                input_model=ChangeMetadataPdfArgs,
            ),
            "stirling_change_permissions": ToolDefinition(
                name="stirling_change_permissions",
                description="Apply PDF permission flags through the password/security backend endpoint.",
                input_model=ChangePermissionsPdfArgs,
            ),
            "stirling_remove_certificate_signatures": ToolDefinition(
                name="stirling_remove_certificate_signatures",
                description="Remove certificate signatures from a PDF after explicit confirmation.",
                input_model=ConfirmedPdfArgs,
            ),
            "stirling_unlock_pdf_forms": ToolDefinition(
                name="stirling_unlock_pdf_forms",
                description="Unlock PDF form fields after explicit confirmation.",
                input_model=ConfirmedPdfArgs,
            ),
            "stirling_add_attachments": ToolDefinition(
                name="stirling_add_attachments",
                description="Embed one or more local files as PDF attachments.",
                input_model=AddAttachmentsPdfArgs,
            ),
            "stirling_edit_table_of_contents": ToolDefinition(
                name="stirling_edit_table_of_contents",
                description="Apply a bookmark/table-of-contents outline to a PDF.",
                input_model=EditTableOfContentsPdfArgs,
            ),
            "stirling_remove_blank_pages": ToolDefinition(
                name="stirling_remove_blank_pages",
                description="Detect blank pages and return a ZIP with non-blank and blank-page PDFs.",
                input_model=RemoveBlanksPdfArgs,
            ),
            "stirling_split_scanned_photos": ToolDefinition(
                name="stirling_split_scanned_photos",
                description="Detect and split scanned photos from a PDF/image through the backend OpenCV flow.",
                input_model=ScannerImageSplitPdfArgs,
            ),
            "stirling_replace_colors": ToolDefinition(
                name="stirling_replace_colors",
                description="Replace, invert, or convert PDF colors through the backend.",
                input_model=ReplaceColorPdfArgs,
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
            raise McpProtocolError(-32602, f"Unknown tool: {name}")
        payload = arguments or {}
        try:
            if name == "stirling_health_check":
                args = HealthCheckArgs.model_validate(payload)
                result = self.health_checker.run(args)
            elif name == "stirling_setup_diagnostics":
                SetupDiagnosticsArgs.model_validate(payload)
                result = self._setup_diagnostics()
            elif name == "stirling_discover_pdfs":
                args = DiscoverPdfsArgs.model_validate(payload)
                result = self._discover_pdfs(args)
            elif name == "stirling_list_operations":
                NoArgs.model_validate(payload)
                result = self._list_operations()
            elif name == "stirling_list_executable_operations":
                NoArgs.model_validate(payload)
                result = self._list_executable_operations()
            elif name == "stirling_operation_coverage":
                args = OperationCoverageArgs.model_validate(payload)
                result = self._operation_coverage(args)
            elif name == "stirling_cleanup_mcp_output":
                args = CleanupMcpOutputArgs.model_validate(payload)
                result = self._cleanup_mcp_output(args)
            elif name == "stirling_get_operation_details":
                args = GetOperationDetailsArgs.model_validate(payload)
                result = self._get_operation_details(args.operation_id)
            elif name == "stirling_plan_edit_request":
                args = PlanEditRequestArgs.model_validate(payload)
                result = self._plan_edit_request(args)
            elif name == "stirling_execute_plan":
                args = ExecutePlanArgs.model_validate(payload)
                result = self._execute_plan(args)
            elif name == "stirling_answer_pdf_question":
                args = AnswerPdfQuestionArgs.model_validate(payload)
                result = self._answer_pdf_question(args)
            elif name == "stirling_read_pdf_editor_document":
                args = ReadPdfEditorDocumentArgs.model_validate(payload)
                result = self._read_pdf_editor_document(args)
            elif name == "stirling_call_endpoint":
                args = CallEndpointArgs.model_validate(payload)
                result = self._call_endpoint(args)
            elif name == "stirling_execute_operation":
                args = ExecuteOperationArgs.model_validate(payload)
                result = self._execute_operation(args)
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
            elif name == "stirling_split_pdf":
                args = SplitPdfArgs.model_validate(payload)
                result = self._split_pdf(args)
            elif name == "stirling_extract_images":
                args = ExtractImagesArgs.model_validate(payload)
                result = self._extract_images(args)
            elif name == "stirling_ocr_pdf":
                args = OcrPdfArgs.model_validate(payload)
                result = self._ocr_pdf(args)
            elif name == "stirling_convert_file":
                args = ConvertFileArgs.model_validate(payload)
                result = self._convert_file(args)
            elif name == "stirling_add_watermark":
                args = WatermarkPdfArgs.model_validate(payload)
                result = self._add_watermark(args)
            elif name == "stirling_add_password":
                args = AddPasswordArgs.model_validate(payload)
                result = self._add_password(args)
            elif name == "stirling_remove_password":
                args = RemovePasswordArgs.model_validate(payload)
                result = self._remove_password(args)
            elif name == "stirling_repair_pdf":
                args = RepairPdfArgs.model_validate(payload)
                result = self._repair_pdf(args)
            elif name == "stirling_sanitize_pdf":
                args = SanitizePdfArgs.model_validate(payload)
                result = self._sanitize_pdf(args)
            elif name == "stirling_flatten_pdf":
                args = FlattenPdfArgs.model_validate(payload)
                result = self._flatten_pdf(args)
            elif name == "stirling_extract_pages":
                args = ExtractPagesPdfArgs.model_validate(payload)
                result = self._extract_pages(args)
            elif name == "stirling_crop_pdf":
                args = CropPdfArgs.model_validate(payload)
                result = self._crop_pdf(args)
            elif name == "stirling_scale_pages":
                args = ScalePagesPdfArgs.model_validate(payload)
                result = self._scale_pages(args)
            elif name == "stirling_redact_pdf":
                args = RedactPdfArgs.model_validate(payload)
                result = self._redact_pdf(args)
            elif name == "stirling_reorganize_pages":
                args = ReorganizePagesPdfArgs.model_validate(payload)
                result = self._reorganize_pages(args)
            elif name == "stirling_overlay_pdfs":
                args = OverlayPdfsArgs.model_validate(payload)
                result = self._overlay_pdfs(args)
            elif name == "stirling_page_layout":
                args = PageLayoutPdfArgs.model_validate(payload)
                result = self._page_layout(args)
            elif name == "stirling_booklet_pdf":
                args = BookletPdfArgs.model_validate(payload)
                result = self._booklet_pdf(args)
            elif name == "stirling_sign_pdf":
                args = SignPdfArgs.model_validate(payload)
                result = self._sign_pdf(args)
            elif name == "stirling_cert_sign_pdf":
                args = CertSignPdfArgs.model_validate(payload)
                result = self._cert_sign_pdf(args)
            elif name == "stirling_change_metadata":
                args = ChangeMetadataPdfArgs.model_validate(payload)
                result = self._change_metadata(args)
            elif name == "stirling_change_permissions":
                args = ChangePermissionsPdfArgs.model_validate(payload)
                result = self._change_permissions(args)
            elif name == "stirling_remove_certificate_signatures":
                args = ConfirmedPdfArgs.model_validate(payload)
                result = self._remove_certificate_signatures(args)
            elif name == "stirling_unlock_pdf_forms":
                args = ConfirmedPdfArgs.model_validate(payload)
                result = self._unlock_pdf_forms(args)
            elif name == "stirling_add_attachments":
                args = AddAttachmentsPdfArgs.model_validate(payload)
                result = self._add_attachments(args)
            elif name == "stirling_edit_table_of_contents":
                args = EditTableOfContentsPdfArgs.model_validate(payload)
                result = self._edit_table_of_contents(args)
            elif name == "stirling_remove_blank_pages":
                args = RemoveBlanksPdfArgs.model_validate(payload)
                result = self._remove_blank_pages(args)
            elif name == "stirling_split_scanned_photos":
                args = ScannerImageSplitPdfArgs.model_validate(payload)
                result = self._split_scanned_photos(args)
            elif name == "stirling_replace_colors":
                args = ReplaceColorPdfArgs.model_validate(payload)
                result = self._replace_colors(args)
            else:
                raise McpToolError(f"Unhandled MCP tool: {name}")
        except ValidationError as exc:
            raise McpProtocolError(
                -32602,
                f"Invalid arguments for tool: {name}",
                _normalize_json_value(exc.errors()),
            ) from exc
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
            {
                "uri": "stirling://mcp/workflows",
                "name": "Stirling MCP Workflow Examples",
                "mimeType": "text/markdown",
                "description": "Prompt examples for common local PDF workflows.",
            },
        ]

    def read_resource(self, uri: str) -> dict[str, JsonValue]:
        if uri == "stirling://operations/catalog":
            payload = json.dumps(self._list_operations(), ensure_ascii=True, indent=2)
            mime_type = "application/json"
        elif uri == "stirling://mcp/readme":
            payload = _MCP_README_PATH.read_text(encoding="utf-8") if _MCP_README_PATH.exists() else ""
            mime_type = "text/markdown"
        elif uri == "stirling://mcp/workflows":
            payload = self._workflow_examples()
            mime_type = "text/markdown"
        else:
            raise McpProtocolError(-32602, f"Unknown MCP resource: {uri}")
        return {
            "contents": [
                {
                    "uri": uri,
                    "mimeType": mime_type,
                    "text": payload,
                }
            ]
        }

    def _workflow_examples(self) -> str:
        return """# Stirling MCP Workflow Examples

## Inspect Available Wrappers
Call `stirling_list_executable_operations` before using generic endpoints. Prefer a typed wrapper when available.

## Rotate And Compress
1. Call `stirling_rotate_pdf` with `angle=90`.
2. Call `stirling_compress_pdf` on the rotated output with `compression_method=quality`.

## Merge Then Sanitize
1. Call `stirling_merge_pdfs` with PDFs in the desired order.
2. Call `stirling_sanitize_pdf` on the merged output.

## Cleanup
Call `stirling_cleanup_mcp_output` with `dry_run=true` first, then repeat with `dry_run=false`.
"""

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

    def _list_executable_operations(self) -> dict[str, JsonValue]:
        wrappers = self._executable_operation_map()
        generic = self._generic_operation_endpoint_map()
        operation_ids = sorted(set(wrappers) | set(generic))
        return {
            "count": len(operation_ids),
            "firstClassWrapperCount": len(wrappers),
            "genericOperationCount": len(generic),
            "operations": [
                {
                    "operationId": operation_id,
                    "toolName": wrappers.get(operation_id, {}).get("toolName", "stirling_execute_operation"),
                    "endpoint": wrappers.get(operation_id, {}).get("endpoint", generic.get(operation_id, "")),
                    "execution": "firstClassWrapper" if operation_id in wrappers else "genericOperation",
                    "notes": wrappers.get(operation_id, {}).get(
                        "notes",
                        "Use stirling_execute_operation with operation_id, files, and form_fields.",
                    ),
                }
                for operation_id in operation_ids
            ],
        }

    def _operation_coverage(self, args: OperationCoverageArgs) -> dict[str, JsonValue]:
        wrappers = self._executable_operation_map()
        generic = self._generic_operation_endpoint_map()
        operation_ids = sorted(str(operation_id) for operation_id in self.tool_catalog.get_catalog().operation_ids)
        operations: JsonArray = []
        counts: dict[str, int] = {}
        for operation_id in operation_ids:
            metadata = self.metadata_resolver.get(operation_id)
            classification = self._operation_coverage_classification(operation_id, metadata, wrappers, generic)
            coverage = classification["coverage"]
            if not isinstance(coverage, str):
                raise McpToolError(f"Invalid coverage classification for operation: {operation_id}")
            counts[coverage] = counts.get(coverage, 0) + 1
            item: JsonObject = {
                "operationId": operation_id,
                **classification,
            }
            if args.include_frontend_metadata:
                item["frontendMetadata"] = metadata.to_dict() if metadata else None
            operations.append(item)
        return {
            "count": len(operations),
            "counts": _normalize_json_value(dict(sorted(counts.items()))),
            "operations": operations,
        }

    def _operation_coverage_classification(
        self,
        operation_id: str,
        metadata: OperationFrontendMetadata | None,
        wrappers: dict[str, dict[str, str]],
        generic: dict[str, str],
    ) -> JsonObject:
        if operation_id in wrappers:
            return {
                "coverage": "firstClassWrapper",
                "toolName": wrappers[operation_id]["toolName"],
                "endpoint": wrappers[operation_id]["endpoint"],
                "notes": wrappers[operation_id].get("notes", ""),
            }
        if operation_id in generic:
            return {
                "coverage": "genericStaticEndpoint",
                "toolName": "stirling_execute_operation",
                "endpoint": generic[operation_id],
                "notes": "Executable through stirling_execute_operation; add a typed wrapper for production ergonomics.",
            }
        if metadata and metadata.custom_processor_name:
            return {
                "coverage": "clientOnly",
                "toolName": "",
                "endpoint": "",
                "notes": "Frontend uses a browser custom processor; no Java backend endpoint is exposed for MCP.",
            }
        if metadata and metadata.endpoint_expression:
            return {
                "coverage": "dynamicEndpoint",
                "toolName": "stirling_execute_operation",
                "endpoint": metadata.endpoint_expression,
                "notes": "Endpoint is not a static string; use a typed wrapper or explicit endpoint override.",
            }
        return {
            "coverage": "notExecutable",
            "toolName": "",
            "endpoint": "",
            "notes": "No static backend endpoint or first-class wrapper is known.",
        }

    def _executable_operation_map(self) -> dict[str, dict[str, str]]:
        return {
            "rotate": {"toolName": "stirling_rotate_pdf", "endpoint": "/api/v1/general/rotate-pdf"},
            "merge": {"toolName": "stirling_merge_pdfs", "endpoint": "/api/v1/general/merge-pdfs"},
            "compress": {"toolName": "stirling_compress_pdf", "endpoint": "/api/v1/misc/compress-pdf"},
            "removePages": {"toolName": "stirling_remove_pages", "endpoint": "/api/v1/general/remove-pages"},
            "split": {"toolName": "stirling_split_pdf", "endpoint": "/api/v1/general/split-pages"},
            "extractImages": {"toolName": "stirling_extract_images", "endpoint": "/api/v1/misc/extract-images"},
            "ocr": {"toolName": "stirling_ocr_pdf", "endpoint": "/api/v1/misc/ocr-pdf"},
            "convert": {"toolName": "stirling_convert_file", "endpoint": "varies by from_extension/to_extension"},
            "watermark": {"toolName": "stirling_add_watermark", "endpoint": "/api/v1/security/add-watermark"},
            "addPassword": {"toolName": "stirling_add_password", "endpoint": "/api/v1/security/add-password"},
            "removePassword": {"toolName": "stirling_remove_password", "endpoint": "/api/v1/security/remove-password"},
            "repair": {"toolName": "stirling_repair_pdf", "endpoint": "/api/v1/misc/repair"},
            "sanitize": {"toolName": "stirling_sanitize_pdf", "endpoint": "/api/v1/security/sanitize-pdf"},
            "flatten": {"toolName": "stirling_flatten_pdf", "endpoint": "/api/v1/misc/flatten"},
            "extractPages": {"toolName": "stirling_extract_pages", "endpoint": "/api/v1/general/rearrange-pages"},
            "crop": {"toolName": "stirling_crop_pdf", "endpoint": "/api/v1/general/crop"},
            "scalePages": {"toolName": "stirling_scale_pages", "endpoint": "/api/v1/general/scale-pages"},
            "redact": {"toolName": "stirling_redact_pdf", "endpoint": "/api/v1/security/auto-redact"},
            "reorganizePages": {
                "toolName": "stirling_reorganize_pages",
                "endpoint": "/api/v1/general/rearrange-pages",
            },
            "overlayPdfs": {"toolName": "stirling_overlay_pdfs", "endpoint": "/api/v1/general/overlay-pdfs"},
            "pageLayout": {"toolName": "stirling_page_layout", "endpoint": "/api/v1/general/multi-page-layout"},
            "bookletImposition": {
                "toolName": "stirling_booklet_pdf",
                "endpoint": "/api/v1/general/booklet-imposition",
            },
            "sign": {"toolName": "stirling_sign_pdf", "endpoint": "/api/v1/security/add-signature"},
            "certSign": {"toolName": "stirling_cert_sign_pdf", "endpoint": "/api/v1/security/cert-sign"},
            "changeMetadata": {"toolName": "stirling_change_metadata", "endpoint": "/api/v1/misc/update-metadata"},
            "changePermissions": {
                "toolName": "stirling_change_permissions",
                "endpoint": "/api/v1/security/add-password",
            },
            "removeCertSign": {
                "toolName": "stirling_remove_certificate_signatures",
                "endpoint": "/api/v1/security/remove-cert-sign",
            },
            "unlockPDFForms": {
                "toolName": "stirling_unlock_pdf_forms",
                "endpoint": "/api/v1/misc/unlock-pdf-forms",
            },
            "addAttachments": {
                "toolName": "stirling_add_attachments",
                "endpoint": "/api/v1/misc/add-attachments",
            },
            "editTableOfContents": {
                "toolName": "stirling_edit_table_of_contents",
                "endpoint": "/api/v1/general/edit-table-of-contents",
            },
            "removeBlanks": {
                "toolName": "stirling_remove_blank_pages",
                "endpoint": "/api/v1/misc/remove-blanks",
            },
            "scannerImageSplit": {
                "toolName": "stirling_split_scanned_photos",
                "endpoint": "/api/v1/misc/extract-image-scans",
            },
            "replaceColor": {
                "toolName": "stirling_replace_colors",
                "endpoint": "/api/v1/misc/replace-invert-pdf",
            },
        }

    def _generic_operation_endpoint_map(self) -> dict[str, str]:
        operation_ids = [str(operation_id) for operation_id in self.tool_catalog.get_catalog().operation_ids]
        endpoints = {
            operation_id: endpoint
            for operation_id in operation_ids
            if (endpoint := self._operation_endpoint(operation_id)) is not None
        }
        return dict(sorted(endpoints.items()))

    def _operation_endpoint(self, operation_id: str) -> str | None:
        wrapper_endpoint = self._executable_operation_map().get(operation_id, {}).get("endpoint")
        if wrapper_endpoint and wrapper_endpoint.startswith("/api/v1/"):
            return wrapper_endpoint
        metadata = self.metadata_resolver.get(operation_id)
        if not metadata or not metadata.endpoint_expression:
            return None
        return self._literal_backend_endpoint(metadata.endpoint_expression)

    def _literal_backend_endpoint(self, expression: str) -> str | None:
        try:
            value = literal_eval(expression.strip())
        except (SyntaxError, ValueError):
            return None
        return value if isinstance(value, str) and value.startswith("/api/v1/") else None

    def _cleanup_mcp_output(self, args: CleanupMcpOutputArgs) -> dict[str, JsonValue]:
        roots = [self.endpoint_executor.output_dir / "mcp" / "tmp"]
        if args.include_outputs:
            roots.append(self.endpoint_executor.output_dir / "mcp")
        cutoff = time.time() - (args.max_age_hours * 3600)
        candidates: list[Path] = []
        for root in roots:
            if not root.exists():
                continue
            for path in root.rglob("*"):
                if path.is_file() and path.stat().st_mtime <= cutoff:
                    candidates.append(path)
        deleted: JsonArray = []
        for path in sorted(set(candidates)):
            deleted.append({"path": str(path), "sizeBytes": path.stat().st_size})
            if not args.dry_run:
                path.unlink(missing_ok=True)
        return {
            "dryRun": args.dry_run,
            "maxAgeHours": args.max_age_hours,
            "includeOutputs": args.include_outputs,
            "filesMatched": len(deleted),
            "files": deleted,
        }

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
            "fieldDefaults": _normalize_json_value(
                param_model.model_validate({}).model_dump(by_alias=True, mode="json")
            )
            if param_model
            else {},
            "frontendMetadata": metadata.to_dict() if metadata else None,
        }

    def _setup_diagnostics(self) -> dict[str, JsonValue]:
        engine_command: JsonArray = ["--directory", str(_ENGINE_ROOT), "run", "python", "scripts/mcp_launcher.py"]
        executor = self.endpoint_executor
        cwd = Path.cwd().resolve()
        warnings: JsonArray = []
        if cwd != _ENGINE_ROOT.resolve():
            warnings.append(
                {
                    "code": "cwd",
                    "message": "Current working directory is not engine/. Keep cwd and pass uv --directory.",
                }
            )
        if not _env_value("UV_CACHE_DIR"):
            warnings.append(
                {
                    "code": "uv-cache",
                    "message": "UV_CACHE_DIR is unset. Desktop clients may need an engine-local writable uv cache.",
                }
            )
        if not _env_value("STIRLING_JAVA_BACKEND_URL"):
            warnings.append({"code": "backend-url", "message": "STIRLING_JAVA_BACKEND_URL is unset."})
        client_env: JsonObject = {
            "UV_CACHE_DIR": str((_ENGINE_ROOT / ".uv-cache").resolve()),
            "STIRLING_JAVA_BACKEND_URL": _env_value("STIRLING_JAVA_BACKEND_URL") or "http://localhost:8080",
            "STIRLING_MCP_ALLOWED_ROOTS": os.pathsep.join(str(path) for path in executor.allowed_roots),
        }
        client_hint: JsonObject = {
            "command": "uv",
            "args": engine_command,
            "cwd": str(_ENGINE_ROOT.resolve()),
            "env": client_env,
        }
        return {
            "status": "warn" if warnings else "ready",
            "cwd": str(cwd),
            "engineRoot": str(_ENGINE_ROOT.resolve()),
            "repoRoot": str(_REPO_ROOT.resolve()),
            "backendUrl": _env_value("STIRLING_JAVA_BACKEND_URL"),
            "uvCacheDir": _env_value("UV_CACHE_DIR"),
            "allowedRoots": [str(path) for path in executor.allowed_roots],
            "warnings": warnings,
            "clientConfigHint": client_hint,
        }

    def _discover_pdfs(self, args: DiscoverPdfsArgs) -> dict[str, JsonValue]:
        roots = (
            [self._resolve_directory(path) for path in args.root_paths]
            if args.root_paths
            else [root for root in self.endpoint_executor.allowed_roots if root.exists() and root.is_dir()]
        )
        files: JsonArray = []
        skipped: JsonArray = []
        name_filter = args.name_contains.casefold()
        for root in roots:
            candidates = root.rglob("*.pdf") if args.recursive else root.glob("*.pdf")
            try:
                for candidate in candidates:
                    if len(files) >= args.max_results:
                        break
                    try:
                        resolved = candidate.resolve()
                        self.endpoint_executor._ensure_allowed_path(resolved, "discovered PDF")
                        if not resolved.is_file() or name_filter not in resolved.name.casefold():
                            continue
                        item: JsonObject = {
                            "path": str(resolved),
                            "name": resolved.name,
                            "sizeBytes": resolved.stat().st_size,
                        }
                        if args.include_preflight:
                            try:
                                item["preflight"] = _normalize_json_value(
                                    _get_pdf_preflight(str(resolved)).model_dump(
                                        by_alias=True,
                                        exclude_none=True,
                                        mode="json",
                                    )
                                )
                            except Exception as exc:
                                item["preflightError"] = str(exc)
                        files.append(item)
                    except OSError as exc:
                        skipped.append({"path": str(candidate), "error": str(exc)})
                if len(files) >= args.max_results:
                    break
            except OSError as exc:
                skipped.append({"path": str(root), "error": str(exc)})
        return {
            "roots": [str(root) for root in roots],
            "recursive": args.recursive,
            "nameContains": args.name_contains,
            "count": len(files),
            "truncated": len(files) >= args.max_results,
            "pdfs": files,
            "skipped": skipped,
        }

    def _resolve_directory(self, directory_path: str) -> Path:
        path = Path(directory_path).expanduser()
        if not path.is_absolute():
            path = Path.cwd() / path
        path = path.resolve()
        self.endpoint_executor._ensure_allowed_path(path, "search root")
        if not path.exists():
            raise McpToolError(f"Search root not found: {path}")
        if not path.is_dir():
            raise McpToolError(f"Search root is not a directory: {path}")
        return path

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

    def _execute_plan(self, args: ExecutePlanArgs) -> dict[str, JsonValue]:
        operation_ids = self._plan_operation_ids(args.operations)
        validation = _validate_operation_chain(operation_ids)
        if not validation.is_valid:
            raise McpToolError(validation.error_message or "Plan operation chain is invalid.")
        preflight = self._first_pdf_preflight(args.file_paths)
        risk = _assess_plan_risk(operation_ids, preflight)
        if bool(risk.get("should_confirm")) and not args.confirmed:
            raise McpToolError(
                "Plan requires confirmation. Review the risk details and call again with confirmed=true."
            )

        active_files = list(args.file_paths)
        steps: JsonArray = []
        final_result: dict[str, JsonValue] | None = None
        for index, operation in enumerate(args.operations):
            endpoint = operation.endpoint or self._operation_endpoint(operation.operation_id)
            if not endpoint:
                raise McpToolError(
                    f"Plan step {index + 1} operation '{operation.operation_id}' has no static backend endpoint. "
                    "Use stirling_execute_operation with an endpoint override for this step."
                )
            result = self.endpoint_executor.call_endpoint(
                endpoint=endpoint,
                file_paths=active_files,
                file_field_name=operation.file_field_name,
                extra_file_fields=operation.extra_file_fields,
                form_fields=operation.parameters,
                output_path=args.output_path if index == len(args.operations) - 1 else None,
            )
            final_result = result
            steps.append(
                _normalize_json_value(
                    {
                        "index": index + 1,
                        "operationId": operation.operation_id,
                        "endpoint": endpoint,
                        "inputPaths": active_files,
                        "result": result,
                    }
                )
            )
            if index < len(args.operations) - 1:
                saved_path = result.get("savedPath")
                if not isinstance(saved_path, str) or Path(saved_path).suffix.casefold() != ".pdf":
                    raise McpToolError(
                        f"Plan step {index + 1} did not save a PDF for the next step. "
                        "Split non-PDF or ZIP-producing operations into separate calls."
                    )
                active_files = [saved_path]
        if final_result is None:
            raise McpToolError("Plan did not produce a result.")
        return {
            "status": "completed",
            "confirmed": args.confirmed,
            "validation": {"isValid": True},
            "risk": _normalize_json_value(risk),
            "steps": steps,
            "result": final_result,
        }

    def _plan_operation_ids(
        self,
        operations: list[ExecutePlanOperation],
    ) -> list[models.tool_models.OperationId]:
        operation_ids: list[models.tool_models.OperationId] = []
        for operation in operations:
            try:
                operation_ids.append(models.tool_models.OperationId(operation.operation_id))
            except ValueError as exc:
                raise McpToolError(f"Unknown operation id in plan: {operation.operation_id}") from exc
        return operation_ids

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
        self._require_endpoint_confirmation(args.endpoint, args.confirmed)
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

    def _execute_operation(self, args: ExecuteOperationArgs) -> dict[str, JsonValue]:
        endpoint = args.endpoint or self._operation_endpoint(args.operation_id)
        if not endpoint:
            raise McpToolError(
                f"Operation '{args.operation_id}' does not expose a static backend endpoint. "
                "Pass endpoint explicitly or use stirling_call_endpoint."
            )
        self._require_endpoint_confirmation(endpoint, args.confirmed)
        return self.endpoint_executor.call_endpoint(
            endpoint=endpoint,
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
        client_ids = [f"{index}:{Path(path).name}" for index, path in enumerate(args.pdf_paths)]
        return self.endpoint_executor.call_endpoint(
            endpoint="/api/v1/general/merge-pdfs",
            file_paths=args.pdf_paths,
            file_field_name="fileInput",
            extra_file_fields={},
            form_fields={
                "clientFileIds": json.dumps(client_ids, ensure_ascii=True),
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
        compression_method = self._normalize_compression_method(args.compression_method)
        if compression_method not in {"quality", "fileSize"}:
            raise McpToolError(
                "compression_method must be 'quality', 'fileSize', 'file_size', 'size', or 'target_size'."
            )
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

    def _normalize_compression_method(self, value: str) -> str:
        normalized = value.strip().replace("-", "_").lower()
        if normalized in {"filesize", "file_size", "size", "target_size", "target"}:
            return "fileSize"
        if normalized in {"quality", "optimize", "optimise"}:
            return "quality"
        return value.strip()

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

    def _split_pdf(self, args: SplitPdfArgs) -> dict[str, JsonValue]:
        page_numbers = re.sub(r"\s+", "", args.page_numbers)
        if not page_numbers:
            raise McpToolError("page_numbers must not be empty.")
        return self._call_single_pdf_endpoint(
            "/api/v1/general/split-pages",
            args.pdf_path,
            {"pageNumbers": page_numbers},
            args,
        )

    def _extract_images(self, args: ExtractImagesArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/misc/extract-images",
            args.pdf_path,
            {"format": args.image_format},
            args,
        )

    def _ocr_pdf(self, args: OcrPdfArgs) -> dict[str, JsonValue]:
        languages: JsonArray = [str(language) for language in args.languages]
        form_fields: dict[str, JsonValue] = {
            "languages": languages,
            "ocrType": args.ocr_type,
            "ocrRenderType": args.ocr_render_type,
            "sidecar": args.sidecar,
            "deskew": args.deskew,
            "clean": args.clean,
            "cleanFinal": args.clean_final,
            "removeImagesAfter": args.remove_images_after,
        }
        return self._call_single_pdf_endpoint(
            "/api/v1/misc/ocr-pdf",
            args.pdf_path,
            form_fields,
            args,
        )

    def _convert_file(self, args: ConvertFileArgs) -> dict[str, JsonValue]:
        endpoint = self._convert_endpoint(args.from_extension, args.to_extension)
        form_fields = self._convert_form_fields(args.from_extension, args.to_extension)
        return self.endpoint_executor.call_endpoint(
            endpoint=endpoint,
            file_paths=args.file_paths,
            file_field_name="fileInput",
            extra_file_fields={},
            form_fields=form_fields,
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _add_watermark(self, args: WatermarkPdfArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/security/add-watermark",
            args.pdf_path,
            {
                "watermarkType": "text",
                "watermarkText": args.watermark_text,
                "fontSize": args.font_size,
                "rotation": args.rotation,
                "opacity": args.opacity_percent / 100,
                "widthSpacer": args.width_spacer,
                "heightSpacer": args.height_spacer,
                "alphabet": "",
                "customColor": "",
                "convertPDFToImage": False,
            },
            args,
        )

    def _add_password(self, args: AddPasswordArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/security/add-password",
            args.pdf_path,
            {
                "password": args.password,
                "ownerPassword": args.owner_password or args.password,
                "keyLength": args.key_length,
            },
            args,
        )

    def _remove_password(self, args: RemovePasswordArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/security/remove-password",
            args.pdf_path,
            {"password": args.password},
            args,
        )

    def _repair_pdf(self, args: RepairPdfArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint("/api/v1/misc/repair", args.pdf_path, {}, args)

    def _sanitize_pdf(self, args: SanitizePdfArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/security/sanitize-pdf",
            args.pdf_path,
            {
                "removeJavaScript": args.remove_javascript,
                "removeEmbeddedFiles": args.remove_embedded_files,
                "removeXMPMetadata": args.remove_xmp_metadata,
                "removeMetadata": args.remove_metadata,
                "removeLinks": args.remove_links,
                "removeFonts": args.remove_fonts,
            },
            args,
        )

    def _flatten_pdf(self, args: FlattenPdfArgs) -> dict[str, JsonValue]:
        form_fields: dict[str, JsonValue] = {"flattenOnlyForms": args.flatten_only_forms}
        if args.render_dpi is not None:
            form_fields["renderDpi"] = args.render_dpi
        return self._call_single_pdf_endpoint("/api/v1/misc/flatten", args.pdf_path, form_fields, args)

    def _extract_pages(self, args: ExtractPagesPdfArgs) -> dict[str, JsonValue]:
        page_numbers = re.sub(r"\s+", "", args.page_numbers)
        if not page_numbers:
            raise McpToolError("page_numbers must not be empty.")
        return self._call_single_pdf_endpoint(
            "/api/v1/general/rearrange-pages",
            args.pdf_path,
            {"pageNumbers": page_numbers},
            args,
        )

    def _crop_pdf(self, args: CropPdfArgs) -> dict[str, JsonValue]:
        form_fields: dict[str, JsonValue] = {"autoCrop": args.auto_crop}
        if not args.auto_crop:
            crop_values = {"x": args.x, "y": args.y, "width": args.width, "height": args.height}
            missing = [key for key, value in crop_values.items() if value is None]
            if missing:
                raise McpToolError(f"{', '.join(missing)} are required when auto_crop is false.")
            form_fields.update({key: value for key, value in crop_values.items() if value is not None})
        return self._call_single_pdf_endpoint("/api/v1/general/crop", args.pdf_path, form_fields, args)

    def _scale_pages(self, args: ScalePagesPdfArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/general/scale-pages",
            args.pdf_path,
            {"scaleFactor": args.scale_factor, "pageSize": args.page_size},
            args,
        )

    def _redact_pdf(self, args: RedactPdfArgs) -> dict[str, JsonValue]:
        if not args.confirmed:
            raise McpToolError("Automatic redaction requires confirmed=true.")
        if not any(word.strip() for word in args.words_to_redact):
            raise McpToolError("words_to_redact must include at least one non-empty text line.")
        return self._call_single_pdf_endpoint(
            "/api/v1/security/auto-redact",
            args.pdf_path,
            {
                "listOfText": "\n".join(word for word in args.words_to_redact if word.strip()),
                "useRegex": args.use_regex,
                "wholeWordSearch": args.whole_word_search,
                "redactColor": args.redact_color.removeprefix("#"),
                "customPadding": args.custom_padding,
                "convertPDFToImage": args.convert_pdf_to_image,
            },
            args,
        )

    def _reorganize_pages(self, args: ReorganizePagesPdfArgs) -> dict[str, JsonValue]:
        form_fields: dict[str, JsonValue] = {}
        if args.custom_mode:
            form_fields["customMode"] = args.custom_mode
        if args.page_numbers:
            form_fields["pageNumbers"] = re.sub(r"\s+", "", args.page_numbers)
        if not form_fields:
            raise McpToolError("page_numbers or custom_mode is required.")
        return self._call_single_pdf_endpoint(
            "/api/v1/general/rearrange-pages",
            args.pdf_path,
            form_fields,
            args,
        )

    def _overlay_pdfs(self, args: OverlayPdfsArgs) -> dict[str, JsonValue]:
        form_fields: dict[str, JsonValue] = {
            "overlayMode": args.overlay_mode,
            "overlayPosition": args.overlay_position,
        }
        if args.overlay_mode == "FixedRepeatOverlay":
            form_fields["counts"] = [int(count) for count in args.counts]
        return self.endpoint_executor.call_endpoint(
            endpoint="/api/v1/general/overlay-pdfs",
            file_paths=[args.pdf_path],
            file_field_name="fileInput",
            extra_file_fields={"overlayFiles": args.overlay_pdf_paths},
            form_fields=form_fields,
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _page_layout(self, args: PageLayoutPdfArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/general/multi-page-layout",
            args.pdf_path,
            {"pagesPerSheet": args.pages_per_sheet, "addBorder": args.add_border},
            args,
        )

    def _booklet_pdf(self, args: BookletPdfArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/general/booklet-imposition",
            args.pdf_path,
            {
                "pagesPerSheet": args.pages_per_sheet,
                "addBorder": args.add_border,
                "spineLocation": args.spine_location,
                "addGutter": args.add_gutter,
                "gutterSize": args.gutter_size,
                "doubleSided": args.double_sided,
                "duplexPass": args.duplex_pass,
                "flipOnShortEdge": args.flip_on_short_edge,
            },
            args,
        )

    def _sign_pdf(self, args: SignPdfArgs) -> dict[str, JsonValue]:
        if not args.confirmed:
            raise McpToolError("Visual signing requires confirmed=true.")
        if args.signature_type in {"image", "canvas"} and not args.signature_data:
            raise McpToolError("signature_data is required for image or canvas signatures.")
        if args.signature_type == "text" and not args.signer_name:
            raise McpToolError("signer_name is required for text signatures.")
        if args.signature_type not in {"text", "image", "canvas"}:
            raise McpToolError("signature_type must be text, image, or canvas.")
        form_fields: dict[str, JsonValue] = {"signatureType": args.signature_type}
        if args.signature_data:
            form_fields["signatureData"] = args.signature_data
        if args.signature_position:
            form_fields.update(args.signature_position.model_dump(mode="json"))
        for field_name, value in {
            "reason": args.reason,
            "location": args.location,
            "signerName": args.signer_name,
        }.items():
            if value:
                form_fields[field_name] = value
        return self._call_single_pdf_endpoint("/api/v1/security/add-signature", args.pdf_path, form_fields, args)

    def _cert_sign_pdf(self, args: CertSignPdfArgs) -> dict[str, JsonValue]:
        if not args.confirmed:
            raise McpToolError("Certificate signing requires confirmed=true.")
        sign_mode = args.sign_mode.upper()
        extra_files: dict[str, str | list[str]] = {}
        form_fields: dict[str, JsonValue] = {}
        if sign_mode == "AUTO":
            form_fields["certType"] = "SERVER"
        elif sign_mode == "KMS":
            algorithm = args.kms_signature_algorithm.upper()
            if algorithm not in {"SHA256_WITH_RSA", "SHA256_WITH_ECDSA"}:
                raise McpToolError("kms_signature_algorithm must be SHA256_WITH_RSA or SHA256_WITH_ECDSA.")
            certificate_path = args.kms_cert_path or args.cert_path
            if not certificate_path:
                raise McpToolError("kms_cert_path or cert_path is required for KMS certificate signing.")
            form_fields.update({"certType": "KMS", "kmsSignatureAlgorithm": algorithm})
            if args.kms_key_id:
                form_fields["kmsKeyId"] = args.kms_key_id
            extra_files["certFile"] = certificate_path
        elif sign_mode == "MANUAL":
            cert_type = args.cert_type.upper()
            form_fields.update({"certType": cert_type, "password": args.password})
            if cert_type == "PEM":
                if not args.private_key_path or not args.cert_path:
                    raise McpToolError("private_key_path and cert_path are required for PEM certificate signing.")
                extra_files.update({"privateKeyFile": args.private_key_path, "certFile": args.cert_path})
            elif cert_type in {"PKCS12", "PFX"}:
                if not args.p12_path:
                    raise McpToolError("p12_path is required for PKCS12 or PFX certificate signing.")
                extra_files["p12File"] = args.p12_path
            elif cert_type == "JKS":
                if not args.jks_path:
                    raise McpToolError("jks_path is required for JKS certificate signing.")
                extra_files["jksFile"] = args.jks_path
            else:
                raise McpToolError("cert_type must be PEM, PKCS12, PFX, or JKS for manual certificate signing.")
        else:
            raise McpToolError("sign_mode must be MANUAL, AUTO, or KMS.")
        if args.show_signature:
            form_fields.update(
                {
                    "showSignature": True,
                    "reason": args.reason,
                    "location": args.location,
                    "name": args.name,
                    "pageNumber": args.page_number,
                    "showLogo": args.show_logo,
                }
            )
        return self.endpoint_executor.call_endpoint(
            endpoint="/api/v1/security/cert-sign",
            file_paths=[args.pdf_path],
            file_field_name="fileInput",
            extra_file_fields=extra_files,
            form_fields=form_fields,
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _change_metadata(self, args: ChangeMetadataPdfArgs) -> dict[str, JsonValue]:
        form_fields: dict[str, JsonValue] = {
            "title": args.title,
            "author": args.author,
            "subject": args.subject,
            "keywords": args.keywords,
            "creator": args.creator,
            "producer": args.producer,
            "creationDate": args.creation_date,
            "modificationDate": args.modification_date,
            "deleteAll": args.delete_all,
        }
        if args.trapped:
            form_fields["trapped"] = args.trapped
        key_number = 0
        for entry in args.custom_metadata:
            key = entry.key.strip()
            value = entry.value.strip()
            if key and value:
                key_number += 1
                form_fields[f"allRequestParams[customKey{key_number}]"] = key
                form_fields[f"allRequestParams[customValue{key_number}]"] = value
        return self._call_single_pdf_endpoint("/api/v1/misc/update-metadata", args.pdf_path, form_fields, args)

    def _change_permissions(self, args: ChangePermissionsPdfArgs) -> dict[str, JsonValue]:
        if not args.confirmed:
            raise McpToolError("Permission changes require confirmed=true.")
        return self._call_single_pdf_endpoint(
            "/api/v1/security/add-password",
            args.pdf_path,
            {
                "password": args.password,
                "ownerPassword": args.owner_password,
                "keyLength": args.key_length,
                "preventAssembly": args.prevent_assembly,
                "preventExtractContent": args.prevent_extract_content,
                "preventExtractForAccessibility": args.prevent_extract_for_accessibility,
                "preventFillInForm": args.prevent_fill_in_form,
                "preventModify": args.prevent_modify,
                "preventModifyAnnotations": args.prevent_modify_annotations,
                "preventPrinting": args.prevent_printing,
                "preventPrintingFaithful": args.prevent_printing_faithful,
            },
            args,
        )

    def _remove_certificate_signatures(self, args: ConfirmedPdfArgs) -> dict[str, JsonValue]:
        self._require_confirmed(args, "Certificate signature removal")
        return self._call_single_pdf_endpoint("/api/v1/security/remove-cert-sign", args.pdf_path, {}, args)

    def _unlock_pdf_forms(self, args: ConfirmedPdfArgs) -> dict[str, JsonValue]:
        self._require_confirmed(args, "PDF form unlocking")
        return self._call_single_pdf_endpoint("/api/v1/misc/unlock-pdf-forms", args.pdf_path, {}, args)

    def _add_attachments(self, args: AddAttachmentsPdfArgs) -> dict[str, JsonValue]:
        return self.endpoint_executor.call_endpoint(
            endpoint="/api/v1/misc/add-attachments",
            file_paths=[args.pdf_path],
            file_field_name="fileInput",
            extra_file_fields={"attachments": args.attachment_paths},
            form_fields={"convertToPdfA3b": args.convert_to_pdfa3b},
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _edit_table_of_contents(self, args: EditTableOfContentsPdfArgs) -> dict[str, JsonValue]:
        bookmark_data = [self._bookmark_payload(bookmark) for bookmark in args.bookmarks]
        return self._call_single_pdf_endpoint(
            "/api/v1/general/edit-table-of-contents",
            args.pdf_path,
            {
                "replaceExisting": args.replace_existing,
                "bookmarkData": json.dumps(bookmark_data, ensure_ascii=True),
            },
            args,
        )

    def _bookmark_payload(self, bookmark: BookmarkArgs) -> JsonObject:
        return {
            "title": bookmark.title,
            "pageNumber": bookmark.page_number,
            "children": [self._bookmark_payload(child) for child in bookmark.children],
        }

    def _remove_blank_pages(self, args: RemoveBlanksPdfArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/misc/remove-blanks",
            args.pdf_path,
            {"threshold": args.threshold, "whitePercent": args.white_percent},
            args,
        )

    def _split_scanned_photos(self, args: ScannerImageSplitPdfArgs) -> dict[str, JsonValue]:
        return self._call_single_pdf_endpoint(
            "/api/v1/misc/extract-image-scans",
            args.pdf_path,
            {
                "angle_threshold": args.angle_threshold,
                "tolerance": args.tolerance,
                "min_area": args.min_area,
                "min_contour_area": args.min_contour_area,
                "border_size": args.border_size,
            },
            args,
        )

    def _replace_colors(self, args: ReplaceColorPdfArgs) -> dict[str, JsonValue]:
        option = args.replace_and_invert_option.upper()
        allowed_options = {"HIGH_CONTRAST_COLOR", "CUSTOM_COLOR", "FULL_INVERSION", "COLOR_SPACE_CONVERSION"}
        if option not in allowed_options:
            raise McpToolError(
                "replace_and_invert_option must be HIGH_CONTRAST_COLOR, CUSTOM_COLOR, "
                "FULL_INVERSION, or COLOR_SPACE_CONVERSION."
            )
        form_fields: dict[str, JsonValue] = {"replaceAndInvertOption": option}
        if option == "HIGH_CONTRAST_COLOR":
            form_fields["highContrastColorCombination"] = args.high_contrast_color_combination
        elif option == "CUSTOM_COLOR":
            form_fields["textColor"] = args.text_color
            form_fields["backGroundColor"] = args.background_color
        return self._call_single_pdf_endpoint("/api/v1/misc/replace-invert-pdf", args.pdf_path, form_fields, args)

    def _require_confirmed(self, args: ConfirmedPdfArgs, label: str) -> None:
        if not args.confirmed:
            raise McpToolError(f"{label} requires confirmed=true.")

    def _require_endpoint_confirmation(self, endpoint: str, confirmed: bool) -> None:
        high_risk_endpoints = {
            "/api/v1/security/add-password",
            "/api/v1/security/remove-password",
            "/api/v1/security/auto-redact",
            "/api/v1/security/add-signature",
            "/api/v1/security/cert-sign",
            "/api/v1/security/remove-cert-sign",
            "/api/v1/security/sanitize-pdf",
            "/api/v1/misc/unlock-pdf-forms",
        }
        endpoint_path = endpoint.split("?", 1)[0]
        if endpoint_path in high_risk_endpoints and not confirmed:
            raise McpToolError(f"Endpoint {endpoint_path} requires confirmed=true.")

    def _call_single_pdf_endpoint(
        self,
        endpoint: str,
        pdf_path: str,
        form_fields: dict[str, JsonValue],
        args: Any,
    ) -> dict[str, JsonValue]:
        return self.endpoint_executor.call_endpoint(
            endpoint=endpoint,
            file_paths=[pdf_path],
            file_field_name="fileInput",
            extra_file_fields={},
            form_fields=form_fields,
            output_path=args.output_path,
            async_job=args.async_job,
            wait_for_job=args.wait_for_job,
            poll_interval_seconds=args.poll_interval_seconds,
            poll_timeout_seconds=args.poll_timeout_seconds,
        )

    def _convert_endpoint(self, from_extension: str, to_extension: str) -> str:
        source = from_extension.lower().lstrip(".")
        target = "pdfa" if to_extension.lower().lstrip(".") == "pdfx" else to_extension.lower().lstrip(".")
        endpoint_map = {
            ("pdf", "png"): "/api/v1/convert/pdf/img",
            ("pdf", "jpg"): "/api/v1/convert/pdf/img",
            ("pdf", "jpeg"): "/api/v1/convert/pdf/img",
            ("pdf", "docx"): "/api/v1/convert/pdf/word",
            ("pdf", "odt"): "/api/v1/convert/pdf/word",
            ("pdf", "pptx"): "/api/v1/convert/pdf/presentation",
            ("pdf", "odp"): "/api/v1/convert/pdf/presentation",
            ("pdf", "txt"): "/api/v1/convert/pdf/text",
            ("pdf", "rtf"): "/api/v1/convert/pdf/text",
            ("pdf", "csv"): "/api/v1/convert/pdf/csv",
            ("pdf", "xlsx"): "/api/v1/convert/pdf/xlsx",
            ("pdf", "html"): "/api/v1/convert/pdf/html",
            ("pdf", "xml"): "/api/v1/convert/pdf/xml",
            ("pdf", "pdfa"): "/api/v1/convert/pdf/pdfa",
            ("html", "pdf"): "/api/v1/convert/html/pdf",
            ("zip", "pdf"): "/api/v1/convert/html/pdf",
            ("markdown", "pdf"): "/api/v1/convert/markdown/pdf",
            ("md", "pdf"): "/api/v1/convert/markdown/pdf",
            ("svg", "pdf"): "/api/v1/convert/svg/pdf",
            ("cbz", "pdf"): "/api/v1/convert/cbz/pdf",
            ("cbr", "pdf"): "/api/v1/convert/cbr/pdf",
            ("pdf", "cbz"): "/api/v1/convert/pdf/cbz",
            ("pdf", "cbr"): "/api/v1/convert/pdf/cbr",
            ("pdf", "epub"): "/api/v1/convert/pdf/epub",
            ("pdf", "azw3"): "/api/v1/convert/pdf/epub",
            ("eml", "pdf"): "/api/v1/convert/eml/pdf",
            ("msg", "pdf"): "/api/v1/convert/eml/pdf",
            ("epub", "pdf"): "/api/v1/convert/ebook/pdf",
            ("mobi", "pdf"): "/api/v1/convert/ebook/pdf",
            ("azw3", "pdf"): "/api/v1/convert/ebook/pdf",
            ("fb2", "pdf"): "/api/v1/convert/ebook/pdf",
        }
        image_sources = {"image", "png", "jpg", "jpeg", "gif", "tiff", "bmp", "webp"}
        office_sources = {"docx", "doc", "odt", "xlsx", "xls", "ods", "pptx", "ppt", "odp"}
        if source in image_sources and target == "pdf":
            return "/api/v1/convert/img/pdf"
        if source in office_sources and target == "pdf":
            return "/api/v1/convert/file/pdf"
        endpoint = endpoint_map.get((source, target))
        if endpoint:
            return endpoint
        raise McpToolError(f"Unsupported conversion: {from_extension} to {to_extension}.")

    def _convert_form_fields(self, from_extension: str, to_extension: str) -> dict[str, JsonValue]:
        source = from_extension.lower().lstrip(".")
        target = to_extension.lower().lstrip(".")
        if source == "pdf" and target in {"png", "jpg", "jpeg"}:
            return {"imageFormat": target, "colorType": "color", "dpi": 150, "singleOrMultiple": "multiple"}
        if source == "pdf" and target in {"docx", "odt", "pptx", "odp", "txt", "rtf"}:
            return {"outputFormat": target}
        if source == "pdf" and target in {"pdfa", "pdfx"}:
            return {"outputFormat": "pdfx" if target == "pdfx" else "pdfa", "strict": False}
        if source == "pdf" and target in {"csv", "xlsx"}:
            return {"pageNumbers": "all"}
        if source in {"image", "png", "jpg", "jpeg", "gif", "tiff", "bmp", "webp"} and target == "pdf":
            return {"fitOption": "fitDocumentToPage", "colorType": "color", "autoRotate": True}
        if source == "svg" and target == "pdf":
            return {"combineIntoSinglePdf": True}
        if source in {"html", "zip"} and target == "pdf":
            return {"zoom": 1.0}
        if source in {"eml", "msg"} and target == "pdf":
            return {
                "includeAttachments": True,
                "maxAttachmentSizeMB": 10,
                "downloadHtml": False,
                "includeAllRecipients": True,
            }
        if source in {"epub", "mobi", "azw3", "fb2"} and target == "pdf":
            return {
                "embedAllFonts": False,
                "includeTableOfContents": True,
                "includePageNumbers": True,
                "optimizeForEbook": True,
            }
        if source == "pdf" and target in {"epub", "azw3"}:
            return {
                "detectChapters": True,
                "targetDevice": "TABLET_PHONE_IMAGES",
                "outputFormat": "AZW3" if target == "azw3" else "EPUB",
            }
        if source == "pdf" and target in {"cbz", "cbr"}:
            return {"dpi": 150}
        if source in {"cbz", "cbr"} and target == "pdf":
            return {"optimizeForEbook": True}
        return {}

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
        self.endpoint_executor._ensure_allowed_path(path, "input file")
        if not path.exists():
            raise McpToolError(f"File not found: {path}")
        if not path.is_file():
            raise McpToolError(f"Path is not a file: {path}")
        return path
