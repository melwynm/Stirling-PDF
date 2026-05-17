from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from dotenv import load_dotenv

from mcp_support import McpProtocolError, McpToolError, StirlingMcpToolRegistry

load_dotenv(Path(__file__).resolve().parents[1] / ".env")


class JsonRpcError(RuntimeError):
    def __init__(self, code: int, message: str, data: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data


class StirlingMcpServer:
    SUPPORTED_PROTOCOL_VERSIONS = (
        "2024-11-05",
        "2025-03-26",
        "2025-06-18",
        "2025-11-25",
    )

    def __init__(self, registry: StirlingMcpToolRegistry | None = None) -> None:
        self.registry = registry or StirlingMcpToolRegistry()
        self._initialized = False

    def run(self) -> None:
        while True:
            try:
                message = self._read_message()
            except JsonRpcError as exc:
                self._write_message(self._error(None, exc.code, exc.message, exc.data))
                continue
            if message is None:
                return
            response = self.handle_message(message)
            if response is not None:
                self._write_message(response)

    def handle_message(self, message: Any) -> dict[str, Any] | list[dict[str, Any]] | None:
        if isinstance(message, list):
            if not message:
                return self._error(None, -32600, "Invalid Request")
            responses = [response for item in message if (response := self._handle_single_message(item)) is not None]
            return responses or None
        return self._handle_single_message(message)

    def _handle_single_message(self, message: Any) -> dict[str, Any] | None:
        if not isinstance(message, dict):
            return self._error(None, -32600, "Invalid Request")

        message_id = self._response_id(message)
        is_notification = "id" not in message
        method = message.get("method")
        try:
            if message.get("jsonrpc") != "2.0":
                raise JsonRpcError(-32600, "Invalid Request")
            if not isinstance(method, str):
                raise JsonRpcError(-32600, "Invalid Request")
            if "id" in message and not self._is_request_id(message["id"]):
                raise JsonRpcError(-32600, "Invalid Request")
            params = message.get("params", {})

            if method == "initialize":
                if is_notification:
                    return None
                self._require_params_object(params)
                return self._success(
                    message_id,
                    {
                        "protocolVersion": self._negotiate_protocol_version(params.get("protocolVersion")),
                        "capabilities": {
                            "tools": {},
                            "resources": {},
                        },
                        "serverInfo": {
                            "name": self.registry.SERVER_NAME,
                            "version": self.registry.SERVER_VERSION,
                        },
                    },
                )
            if method in {"notifications/initialized", "initialized"}:
                self._initialized = True
                return None
            if method == "notifications/cancelled":
                return None
            if is_notification:
                return None
            if method == "ping":
                return self._success(message_id, {})
            if method == "tools/list":
                self._require_params_object(params)
                return self._success(message_id, {"tools": self.registry.list_tools()})
            if method == "tools/call":
                self._require_params_object(params)
                name = params.get("name")
                if not isinstance(name, str):
                    raise JsonRpcError(-32602, "tools/call requires a string tool name")
                arguments = params.get("arguments")
                if arguments is not None and not isinstance(arguments, dict):
                    raise JsonRpcError(-32602, "tools/call arguments must be an object")
                return self._success(message_id, self.registry.call_tool(name, arguments))
            if method == "resources/list":
                self._require_params_object(params)
                return self._success(message_id, {"resources": self.registry.list_resources()})
            if method == "resources/read":
                self._require_params_object(params)
                uri = params.get("uri")
                if not isinstance(uri, str):
                    raise JsonRpcError(-32602, "resources/read requires a string uri")
                return self._success(message_id, self.registry.read_resource(uri))
            if method == "resources/templates/list":
                self._require_params_object(params)
                return self._success(message_id, {"resourceTemplates": []})

            raise JsonRpcError(-32601, f"Method not found: {method}")
        except JsonRpcError as exc:
            return None if is_notification else self._error(message_id, exc.code, exc.message, exc.data)
        except McpProtocolError as exc:
            return None if is_notification else self._error(message_id, exc.code, exc.message, exc.data)
        except McpToolError as exc:
            if method == "tools/call" and not is_notification:
                return self._success(
                    message_id,
                    {
                        "content": [{"type": "text", "text": str(exc)}],
                        "isError": True,
                    },
                )
            return None if is_notification else self._error(message_id, -32603, str(exc))
        except Exception as exc:  # pragma: no cover - last-resort protection
            return None if is_notification else self._error(message_id, -32603, str(exc))

    def _require_params_object(self, params: Any) -> None:
        if not isinstance(params, dict):
            raise JsonRpcError(-32602, "params must be an object")

    def _is_request_id(self, value: Any) -> bool:
        return isinstance(value, (str, int)) and not isinstance(value, bool)

    def _response_id(self, message: dict[str, Any]) -> str | int | None:
        message_id = message.get("id")
        return message_id if self._is_request_id(message_id) else None

    def _negotiate_protocol_version(self, requested: Any) -> str:
        if isinstance(requested, str) and requested in self.SUPPORTED_PROTOCOL_VERSIONS:
            return requested
        return self.registry.PROTOCOL_VERSION

    def _success(self, message_id: Any, result: Any) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": message_id, "result": result}

    def _error(self, message_id: Any, code: int, message: str, data: Any = None) -> dict[str, Any]:
        error: dict[str, Any] = {"code": code, "message": message}
        if data is not None:
            error["data"] = data
        return {"jsonrpc": "2.0", "id": message_id, "error": error}

    def _read_message(self) -> Any | None:
        while True:
            line = sys.stdin.buffer.readline()
            if not line:
                return None
            if line.strip():
                break

        if line.lower().startswith(b"content-length:"):
            return self._read_content_length_message(line)

        try:
            return json.loads(line.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise JsonRpcError(-32700, f"Parse error: {exc}") from exc

    def _read_content_length_message(self, first_line: bytes) -> Any:
        headers: dict[str, str] = {}
        self._read_header_line(headers, first_line)
        while True:
            line = sys.stdin.buffer.readline()
            if not line:
                raise JsonRpcError(-32700, "Unexpected end of input")
            if line in {b"\r\n", b"\n"}:
                break
            self._read_header_line(headers, line)
        content_length = headers.get("content-length")
        if content_length is None:
            raise JsonRpcError(-32700, "Missing Content-Length header")
        try:
            body_length = int(content_length)
        except ValueError as exc:
            raise JsonRpcError(-32700, "Invalid Content-Length header") from exc
        body = sys.stdin.buffer.read(body_length)
        if len(body) != body_length:
            raise JsonRpcError(-32700, "Unexpected end of input")
        try:
            return json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise JsonRpcError(-32700, f"Parse error: {exc}") from exc

    def _read_header_line(self, headers: dict[str, str], line: bytes) -> None:
        decoded = line.decode("utf-8").strip()
        if ":" not in decoded:
            raise JsonRpcError(-32700, "Invalid header")
        key, value = decoded.split(":", 1)
        headers[key.strip().lower()] = value.strip()

    def _write_message(self, payload: dict[str, Any] | list[dict[str, Any]]) -> None:
        encoded = json.dumps(payload, ensure_ascii=True).encode("utf-8")
        sys.stdout.buffer.write(encoded)
        sys.stdout.buffer.write(b"\n")
        sys.stdout.buffer.flush()


def main() -> None:
    StirlingMcpServer().run()


if __name__ == "__main__":
    main()
