from __future__ import annotations

import json
import sys
from typing import Any

from mcp_support import McpToolError, StirlingMcpToolRegistry


class JsonRpcError(RuntimeError):
    def __init__(self, code: int, message: str, data: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data


class StirlingMcpServer:
    def __init__(self, registry: StirlingMcpToolRegistry | None = None) -> None:
        self.registry = registry or StirlingMcpToolRegistry()
        self._initialized = False

    def run(self) -> None:
        while True:
            message = self._read_message()
            if message is None:
                return
            response = self.handle_message(message)
            if response is not None:
                self._write_message(response)

    def handle_message(self, message: dict[str, Any]) -> dict[str, Any] | None:
        try:
            if message.get("jsonrpc") != "2.0":
                raise JsonRpcError(-32600, "Invalid Request")
            method = message.get("method")
            if not isinstance(method, str):
                raise JsonRpcError(-32600, "Invalid Request")
            message_id = message.get("id")
            params = message.get("params", {})

            if method == "initialize":
                return self._success(
                    message_id,
                    {
                        "protocolVersion": self.registry.PROTOCOL_VERSION,
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
            if method == "initialized":
                self._initialized = True
                return None
            if method == "ping":
                return self._success(message_id, {})
            if method == "tools/list":
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
                return self._success(message_id, {"resources": self.registry.list_resources()})
            if method == "resources/read":
                self._require_params_object(params)
                uri = params.get("uri")
                if not isinstance(uri, str):
                    raise JsonRpcError(-32602, "resources/read requires a string uri")
                return self._success(message_id, self.registry.read_resource(uri))

            raise JsonRpcError(-32601, f"Method not found: {method}")
        except JsonRpcError as exc:
            return self._error(message.get("id"), exc.code, exc.message, exc.data)
        except McpToolError as exc:
            if message.get("method") == "tools/call" and message.get("id") is not None:
                return self._success(
                    message["id"],
                    {
                        "content": [{"type": "text", "text": str(exc)}],
                        "isError": True,
                    },
                )
            return self._error(message.get("id"), -32603, str(exc))
        except Exception as exc:  # pragma: no cover - last-resort protection
            return self._error(message.get("id"), -32603, str(exc))

    def _require_params_object(self, params: Any) -> None:
        if not isinstance(params, dict):
            raise JsonRpcError(-32602, "params must be an object")

    def _success(self, message_id: Any, result: Any) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": message_id, "result": result}

    def _error(self, message_id: Any, code: int, message: str, data: Any = None) -> dict[str, Any]:
        error: dict[str, Any] = {"code": code, "message": message}
        if data is not None:
            error["data"] = data
        return {"jsonrpc": "2.0", "id": message_id, "error": error}

    def _read_message(self) -> dict[str, Any] | None:
        headers: dict[str, str] = {}
        while True:
            line = sys.stdin.buffer.readline()
            if not line:
                return None
            if line in {b"\r\n", b"\n"}:
                break
            decoded = line.decode("utf-8").strip()
            if ":" not in decoded:
                continue
            key, value = decoded.split(":", 1)
            headers[key.strip().lower()] = value.strip()
        content_length = headers.get("content-length")
        if content_length is None:
            raise JsonRpcError(-32700, "Missing Content-Length header")
        body = sys.stdin.buffer.read(int(content_length))
        try:
            return json.loads(body.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise JsonRpcError(-32700, f"Invalid JSON: {exc}") from exc

    def _write_message(self, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, ensure_ascii=True).encode("utf-8")
        sys.stdout.buffer.write(f"Content-Length: {len(encoded)}\r\n\r\n".encode())
        sys.stdout.buffer.write(encoded)
        sys.stdout.buffer.flush()


def main() -> None:
    StirlingMcpServer().run()


if __name__ == "__main__":
    main()
