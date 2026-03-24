from __future__ import annotations

import json
import mimetypes
import re
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from pydantic import BaseModel, Field, ValidationError

import models
from config import JAVA_REQUEST_TIMEOUT_SECONDS, OUTPUT_DIR
from editing.constants import assess_plan_risk
from editing.operations import (
    answer_pdf_question,
    build_plan_summary,
    get_pdf_preflight,
    validate_operation_chain,
)
from file_processing_agent import ToolCatalogService
from java_client import java_headers, java_url
from pdf_text_editor import convert_pdf_to_text_editor_document

type JsonPrimitive = str | int | float | bool | None
type JsonObject = dict[str, "JsonValue"]
type JsonArray = list["JsonValue"]
type JsonValue = JsonPrimitive | JsonObject | JsonArray

_MCP_README_PATH = Path(__file__).resolve().parents[1] / "MCP.md"
_REPO_ROOT = Path(__file__).resolve().parents[2]


class McpToolError(RuntimeError):
    pass


def _normalize_json_value(value: Any) -> JsonValue:
    return json.loads(json.dumps(value, ensure_ascii=True))


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


class NoArgs(BaseModel):
    pass


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
        description="Optional destination path for binary responses. Defaults to engine/output/mcp/.",
    )


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


class MultipartEndpointExecutor:
    def __init__(self, output_dir: str = OUTPUT_DIR) -> None:
        self.output_dir = Path(output_dir)

    def call_endpoint(
        self,
        endpoint: str,
        file_paths: list[str],
        file_field_name: str,
        extra_file_fields: dict[str, str | list[str]],
        form_fields: dict[str, JsonValue],
        output_path: str | None,
    ) -> dict[str, JsonValue]:
        if not endpoint.startswith("/api/v1/"):
            raise McpToolError("endpoint must start with /api/v1/.")

        primary_files = [(file_field_name, self._resolve_file_path(file_path)) for file_path in file_paths]
        additional_files: list[tuple[str, Path]] = []
        for field_name, value in extra_file_fields.items():
            values = value if isinstance(value, list) else [value]
            additional_files.extend((field_name, self._resolve_file_path(item)) for item in values)

        body, boundary = self._encode_multipart(form_fields, primary_files + additional_files)
        headers = java_headers()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        headers["Content-Length"] = str(len(body))

        request = urllib.request.Request(java_url(endpoint), data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=JAVA_REQUEST_TIMEOUT_SECONDS) as response:
                raw = response.read()
                content_type = response.headers.get("Content-Type", "application/octet-stream")
                if "application/json" in content_type:
                    text = raw.decode("utf-8") if raw else ""
                    parsed = json.loads(text) if text else {}
                    return {
                        "endpoint": endpoint,
                        "contentType": content_type,
                        "resultJson": parsed,
                    }

                destination = self._resolve_output_path(output_path, response.headers, content_type)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(raw)
                return {
                    "endpoint": endpoint,
                    "contentType": content_type,
                    "savedPath": str(destination),
                    "sizeBytes": len(raw),
                }
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace") if exc.fp else ""
            raise McpToolError(f"Backend request failed with status {exc.code}: {detail or exc.reason}") from exc
        except urllib.error.URLError as exc:
            raise McpToolError(f"Failed to reach Java backend: {exc.reason}") from exc

    def _resolve_file_path(self, file_path: str) -> Path:
        path = Path(file_path).expanduser()
        if not path.is_absolute():
            path = Path.cwd() / path
        path = path.resolve()
        if not path.exists():
            raise McpToolError(f"File not found: {path}")
        if not path.is_file():
            raise McpToolError(f"Path is not a file: {path}")
        return path

    def _encode_multipart(
        self,
        form_fields: dict[str, JsonValue],
        files: list[tuple[str, Path]],
    ) -> tuple[bytes, str]:
        boundary = f"stirling-mcp-{uuid.uuid4().hex}"
        chunks: list[bytes] = []
        for field_name, value in form_fields.items():
            for normalized in self._normalize_field_values(value):
                chunks.append(f"--{boundary}\r\n".encode())
                chunks.append(
                    f'Content-Disposition: form-data; name="{field_name}"\r\n\r\n'.encode()
                )
                chunks.append(normalized.encode("utf-8"))
                chunks.append(b"\r\n")
        for field_name, path in files:
            mime_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
            chunks.append(f"--{boundary}\r\n".encode())
            chunks.append(
                (
                    f'Content-Disposition: form-data; name="{field_name}"; filename="{path.name}"\r\n'
                    f"Content-Type: {mime_type}\r\n\r\n"
                ).encode()
            )
            chunks.append(path.read_bytes())
            chunks.append(b"\r\n")
        chunks.append(f"--{boundary}--\r\n".encode())
        return (b"".join(chunks), boundary)

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
                path = Path.cwd() / path
            return path.resolve()
        filename = self._filename_from_headers(headers) or self._default_filename(content_type)
        return self.output_dir / "mcp" / filename

    def _filename_from_headers(self, headers: Any) -> str | None:
        disposition = headers.get("Content-Disposition")
        if not disposition:
            return None
        match = re.search(r'filename="?([^";]+)"?', disposition)
        return match.group(1) if match else None

    def _default_filename(self, content_type: str) -> str:
        extension = mimetypes.guess_extension(content_type.split(";")[0].strip()) or ".bin"
        return f"stirling-mcp-{uuid.uuid4().hex}{extension}"


class StirlingMcpToolRegistry:
    SERVER_NAME = "stirling-pdf-engine-mcp"
    SERVER_VERSION = "0.1.0"
    PROTOCOL_VERSION = "2024-11-05"

    def __init__(
        self,
        tool_catalog: ToolCatalogService | None = None,
        metadata_resolver: FrontendOperationMetadataResolver | None = None,
        endpoint_executor: MultipartEndpointExecutor | None = None,
    ) -> None:
        self.tool_catalog = tool_catalog or ToolCatalogService()
        self.metadata_resolver = metadata_resolver or FrontendOperationMetadataResolver()
        self.endpoint_executor = endpoint_executor or MultipartEndpointExecutor()
        self._tools = {
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
        }

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
            if name == "stirling_list_operations":
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

        selection = self.tool_catalog.select_edit_tool(
            history=history,
            uploaded_files=uploaded_files,
            preflight=preflight,
        )

        selected_ops: list[tuple[models.tool_models.OperationId, models.tool_models.ParamToolModel | None]] = []
        for operation_id in selection.operation_ids:
            params = self.tool_catalog.extract_operation_parameters(
                operation_id=operation_id,
                previous_operations=selected_ops,
                user_message=args.request,
                history=history,
                preflight=preflight,
            )
            selected_ops.append((operation_id, params))

        operation_ids = [operation_id for operation_id, _ in selected_ops]
        validation = validate_operation_chain(operation_ids)
        risk = assess_plan_risk(operation_ids, preflight)
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
            "summary": _normalize_json_value(build_plan_summary(operation_ids)),
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
            "answer": answer_pdf_question(pdf_path, args.question),
        }

    def _read_pdf_editor_document(self, args: ReadPdfEditorDocumentArgs) -> dict[str, JsonValue]:
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
                return get_pdf_preflight(str(path))
        return None

    def _resolve_path(self, file_path: str) -> Path:
        path = Path(file_path).expanduser()
        if not path.is_absolute():
            path = Path.cwd() / path
        path = path.resolve()
        if not path.exists():
            raise McpToolError(f"File not found: {path}")
        return path
