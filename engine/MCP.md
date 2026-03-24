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
- the existing AI provider settings used by the engine

## Exposed MCP Tools

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
  Calls a backend `/api/v1/` endpoint with multipart form data and saves the binary output.

## Resources

- `stirling://operations/catalog`
- `stirling://mcp/readme`

## Notes

- `stirling_call_endpoint` is intentionally generic so MCP clients can execute backend tools without waiting for a one-tool-per-endpoint wrapper.
- `stirling_get_operation_details` includes the frontend operation hook path and relevant source snippets to help agents construct the correct multipart fields.
