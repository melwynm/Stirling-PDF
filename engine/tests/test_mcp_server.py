from __future__ import annotations

import io
import json
import sys
from typing import cast

from mcp_server import StirlingMcpServer
from mcp_support import StirlingMcpToolRegistry


class FakeRegistry:
    PROTOCOL_VERSION = "2024-11-05"
    SERVER_NAME = "test-server"
    SERVER_VERSION = "0.0.1"

    def list_tools(self):
        return [{"name": "demo", "description": "demo", "inputSchema": {"type": "object"}}]

    def call_tool(self, name, arguments):
        return {
            "content": [{"type": "text", "text": json.dumps({"name": name, "arguments": arguments})}],
            "isError": False,
        }

    def list_resources(self):
        return [{"uri": "stirling://demo", "name": "Demo", "mimeType": "application/json"}]

    def read_resource(self, uri):
        return {"contents": [{"uri": uri, "mimeType": "application/json", "text": "{}"}]}


class _FakeStream:
    def __init__(self, body: bytes = b"") -> None:
        self.buffer = io.BytesIO(body)


def test_initialize_response():
    server = StirlingMcpServer(registry=cast(StirlingMcpToolRegistry, FakeRegistry()))

    response = server.handle_message(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {"protocolVersion": "2024-11-05"},
        }
    )

    assert response == {
        "jsonrpc": "2.0",
        "id": 1,
        "result": {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}, "resources": {}},
            "serverInfo": {"name": "test-server", "version": "0.0.1"},
        },
    }


def test_unsupported_initialize_version_uses_registry_protocol():
    server = StirlingMcpServer(registry=cast(StirlingMcpToolRegistry, FakeRegistry()))

    response = server.handle_message(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {"protocolVersion": "2099-01-01"},
        }
    )

    assert response == {
        "jsonrpc": "2.0",
        "id": 1,
        "result": {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}, "resources": {}},
            "serverInfo": {"name": "test-server", "version": "0.0.1"},
        },
    }


def test_initialized_notification_has_no_response():
    server = StirlingMcpServer(registry=cast(StirlingMcpToolRegistry, FakeRegistry()))

    response = server.handle_message({"jsonrpc": "2.0", "method": "notifications/initialized"})

    assert response is None
    assert server._initialized is True


def test_tools_list_response():
    server = StirlingMcpServer(registry=cast(StirlingMcpToolRegistry, FakeRegistry()))

    response = server.handle_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})

    assert response == {
        "jsonrpc": "2.0",
        "id": 2,
        "result": {"tools": [{"name": "demo", "description": "demo", "inputSchema": {"type": "object"}}]},
    }


def test_unknown_tool_is_protocol_error():
    server = StirlingMcpServer()

    response = server.handle_message(
        {
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/call",
            "params": {"name": "missing_tool", "arguments": {}},
        }
    )

    assert response == {
        "jsonrpc": "2.0",
        "id": 4,
        "error": {"code": -32602, "message": "Unknown tool: missing_tool"},
    }


def test_resources_templates_list_response():
    server = StirlingMcpServer(registry=cast(StirlingMcpToolRegistry, FakeRegistry()))

    response = server.handle_message({"jsonrpc": "2.0", "id": 5, "method": "resources/templates/list"})

    assert response == {"jsonrpc": "2.0", "id": 5, "result": {"resourceTemplates": []}}


def test_batch_omits_notification_responses():
    server = StirlingMcpServer(registry=cast(StirlingMcpToolRegistry, FakeRegistry()))

    response = server.handle_message(
        [
            {"jsonrpc": "2.0", "id": 1, "method": "ping"},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
        ]
    )

    assert response == [{"jsonrpc": "2.0", "id": 1, "result": {}}]


def test_stdio_reads_and_writes_newline_delimited_json(monkeypatch):
    server = StirlingMcpServer(registry=cast(StirlingMcpToolRegistry, FakeRegistry()))
    stdin = _FakeStream(b'{"jsonrpc":"2.0","id":1,"method":"ping"}\n')
    stdout = _FakeStream()
    monkeypatch.setattr(sys, "stdin", stdin)
    monkeypatch.setattr(sys, "stdout", stdout)

    assert server._read_message() == {"jsonrpc": "2.0", "id": 1, "method": "ping"}
    server._write_message({"jsonrpc": "2.0", "id": 1, "result": {}})

    assert stdout.buffer.getvalue() == b'{"jsonrpc": "2.0", "id": 1, "result": {}}\n'


def test_stdio_still_accepts_content_length_input(monkeypatch):
    server = StirlingMcpServer(registry=cast(StirlingMcpToolRegistry, FakeRegistry()))
    body = b'{"jsonrpc":"2.0","id":1,"method":"ping"}'
    stdin = _FakeStream(b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body)
    monkeypatch.setattr(sys, "stdin", stdin)

    assert server._read_message() == {"jsonrpc": "2.0", "id": 1, "method": "ping"}


def test_tools_call_response():
    server = StirlingMcpServer(registry=cast(StirlingMcpToolRegistry, FakeRegistry()))

    response = server.handle_message(
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {"name": "demo", "arguments": {"value": 1}},
        }
    )

    assert response == {
        "jsonrpc": "2.0",
        "id": 3,
        "result": {
            "content": [{"type": "text", "text": '{"name": "demo", "arguments": {"value": 1}}'}],
            "isError": False,
        },
    }
