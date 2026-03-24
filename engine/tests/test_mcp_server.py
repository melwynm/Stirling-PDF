from __future__ import annotations

import json

from mcp_server import StirlingMcpServer


class FakeRegistry:
    PROTOCOL_VERSION = "2024-11-05"
    SERVER_NAME = "test-server"
    SERVER_VERSION = "0.0.1"

    def list_tools(self):
        return [{"name": "demo", "description": "demo", "inputSchema": {"type": "object"}}]

    def call_tool(self, name, arguments):
        return {"content": [{"type": "text", "text": json.dumps({"name": name, "arguments": arguments})}], "isError": False}

    def list_resources(self):
        return [{"uri": "stirling://demo", "name": "Demo", "mimeType": "application/json"}]

    def read_resource(self, uri):
        return {"contents": [{"uri": uri, "mimeType": "application/json", "text": "{}"}]}


def test_initialize_response():
    server = StirlingMcpServer(registry=FakeRegistry())

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


def test_tools_list_response():
    server = StirlingMcpServer(registry=FakeRegistry())

    response = server.handle_message({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})

    assert response == {
        "jsonrpc": "2.0",
        "id": 2,
        "result": {"tools": [{"name": "demo", "description": "demo", "inputSchema": {"type": "object"}}]},
    }


def test_tools_call_response():
    server = StirlingMcpServer(registry=FakeRegistry())

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
