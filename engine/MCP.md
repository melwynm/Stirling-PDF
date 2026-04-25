# Stirling PDF MCP Server

The AI engine now includes a stdio MCP server for agent integrations.

## Run It

From `engine/`:

```bash
make run-mcp
```

It uses the same environment as the normal AI engine, especially:

- `STIRLING_JAVA_BACKEND_URL`
- `STIRLING_JAVA_BACKEND_API_KEY` if your backend requires it
- `STIRLING_MCP_ALLOWED_ROOTS` optional path-list for file access outside the repo/output directories
- the existing AI provider settings used by the engine

The server reads and writes files only under the repository root, `engine/src/output`, and any paths listed in
`STIRLING_MCP_ALLOWED_ROOTS`. Use the platform path separator: `:` on Linux/macOS and `;` on Windows.

## Client Config Examples

Example configs are committed in `engine/examples/`:

- `claude_desktop_config.stirling-pdf.json`
- `cursor-mcp.stirling-pdf.json`

Adjust the absolute repository path, backend URL, and provider keys before using them.

## Exposed MCP Tools

- `stirling_health_check`
  Checks MCP liveness, required engine environment variables, output/temp filesystem access, Java backend health, `pdftohtml`, the rotate-pdf backend probe, and AI provider readiness.
- `stirling_list_operations`
  Lists the operations the engine can plan and describe.
- `stirling_get_operation_details`
  Returns JSON schema, defaults, and frontend hook hints for one operation.
- `stirling_plan_edit_request`
  Converts a natural-language PDF request into operation ids and parameters.
- `stirling_answer_pdf_question`
  Answers a question from a local PDF.
- `stirling_read_pdf_editor_document`
  Converts a local PDF into the structured JSON format used by the PDF text editor.
- `stirling_call_endpoint`
  Calls a backend `/api/v1/` endpoint with multipart form data and saves the binary output. Multipart request bodies are spooled to disk instead of assembled fully in memory.
- `stirling_get_job_status`
  Checks a backend async job and optionally fetches the completed result.
- `stirling_rotate_pdf`
  Convenience wrapper around `/api/v1/general/rotate-pdf` for local PDF rotation.

## Async Jobs

`stirling_call_endpoint` and `stirling_rotate_pdf` support `async_job=true`. Add `wait_for_job=true` to poll until
completion and fetch the final result, or call `stirling_get_job_status` later with the returned job id.

## External PDF Tooling

The engine PDF editor workflow uses `pdftohtml`. Install Poppler and ensure `pdftohtml` is on `PATH`; the
`stirling_health_check` tool reports this explicitly.

## Resources

- `stirling://operations/catalog`
- `stirling://mcp/readme`

## Notes

- MCP clients can use JSON-RPC `ping` for a minimal liveness check, and `stirling_health_check` for dependency readiness.
- `stirling_call_endpoint` is intentionally generic so MCP clients can execute backend tools without waiting for a one-tool-per-endpoint wrapper.
- `stirling_get_operation_details` includes the frontend operation hook path and relevant source snippets to help agents construct the correct multipart fields.
