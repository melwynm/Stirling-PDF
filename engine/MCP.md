# Stirling PDF MCP Server

The AI engine now includes a stdio MCP server for agent integrations.

## Run It

From `engine/`:

```bash
make run-mcp
```

For desktop MCP clients, prefer the launcher entrypoint:

```bash
uv --directory C:\Github\Stirling-PDF\engine run python scripts/mcp_launcher.py
```

The launcher changes into `engine/`, sets `PYTHONUNBUFFERED=1`, defaults `UV_CACHE_DIR` to `engine/.uv-cache`,
and starts `src/mcp_server.py`. This keeps startup stable even when the MCP client launches from a different
working directory.

To check a local MCP process end to end after the Java backend is running:

```bash
make smoke-mcp
```

It uses the same environment as the normal AI engine, especially:

- `STIRLING_JAVA_BACKEND_URL`
- `STIRLING_JAVA_BACKEND_API_KEY` if your backend requires it
- `STIRLING_MCP_ALLOWED_ROOTS` optional path-list for file access outside the repo/output directories
- `STIRLING_MCP_MULTIPART_MODE` optional upload mode: `stream` default, or `spool` for compatibility with servers that reject chunked uploads
- `STIRLING_MCP_MAX_JSON_RESPONSE_BYTES` optional JSON response cap, default 10485760
- the existing AI provider settings used by the engine

The server reads and writes files only under the repository root, `engine/output`, and any paths listed in
`STIRLING_MCP_ALLOWED_ROOTS`. Use the platform path separator: `:` on Linux/macOS and `;` on Windows.

Multipart uploads stream file bytes by default, so request bodies do not require temp disk space proportional to the
input PDFs. Set `STIRLING_MCP_MULTIPART_MODE=spool` only if a backend/proxy requires `Content-Length` for multipart
uploads.

## Client Config Examples

Example configs are committed in `engine/examples/`:

- `claude_desktop_config.stirling-pdf.json`
- `cursor-mcp.stirling-pdf.json`

Adjust the absolute repository path, backend URL, and provider keys before using them.

On Windows, keep `cwd` in the client config and also pass `uv --directory <engine-dir>`.
Some MCP clients launch from `C:\Windows\System32` even when they display a configured working directory.
The examples set `UV_CACHE_DIR` to the engine-local `.uv-cache` directory so a desktop client does not depend on
access to uv's user cache.

After connecting, call `stirling_setup_diagnostics` first when a client disconnects or cannot reach the backend. It
reports the current working directory, engine paths, uv cache setting, backend URL, and ready-to-paste client config
hints without exposing provider keys.

## Exposed MCP Tools

- `stirling_health_check`
  Checks MCP liveness, required engine environment variables, output/temp filesystem access, Java backend health, `pdftohtml`, the rotate-pdf backend probe, and AI provider readiness.
- `stirling_setup_diagnostics`
  Reports MCP startup/config hints for desktop clients, especially Windows working-directory and uv cache issues.
- `stirling_discover_pdfs`
  Lists PDFs under allowed MCP roots with optional preflight metadata before an agent chooses inputs.
- `stirling_list_operations`
  Lists the operations the engine can plan and describe.
- `stirling_list_executable_operations`
  Lists operations that have first-class executable MCP wrappers and their backend endpoints.
- `stirling_operation_coverage`
  Classifies every known operation as first-class, generic static endpoint, client-only, dynamic endpoint, or not executable through MCP yet.
- `stirling_cleanup_mcp_output`
  Deletes old MCP temp/output files. Defaults to dry-run.
- `stirling_get_operation_details`
  Returns JSON schema, defaults, and frontend hook hints for one operation.
- `stirling_plan_edit_request`
  Converts a natural-language PDF request into operation ids and parameters.
- `stirling_execute_plan`
  Runs a validated static-backend operation plan step by step, threading PDF outputs into subsequent steps and requiring explicit confirmation for risky plans.
- `stirling_answer_pdf_question`
  Answers a question from a local PDF.
- `stirling_read_pdf_editor_document`
  Converts a local PDF into the structured JSON format used by the PDF text editor.
- `stirling_call_endpoint`
  Calls a backend `/api/v1/` endpoint with multipart form data and saves the binary output. Multipart request bodies stream from source files by default instead of being assembled fully in memory or on disk.
- `stirling_execute_operation`
  Executes a backend-backed Stirling operation by `operation_id`, resolving static frontend endpoint metadata when a first-class wrapper does not exist.
  Generic endpoint tools require `confirmed=true` for high-risk security, signing, password, sanitization, redaction, and form-unlock endpoints.
- `stirling_get_job_status`
  Checks a backend async job and optionally fetches the completed result.
- `stirling_rotate_pdf`
  Convenience wrapper around `/api/v1/general/rotate-pdf` for local PDF rotation.
- `stirling_merge_pdfs`
  Convenience wrapper around `/api/v1/general/merge-pdfs` for ordered multi-PDF merge.
- `stirling_compress_pdf`
  Convenience wrapper around `/api/v1/misc/compress-pdf` with quality and target-size modes.
- `stirling_remove_pages`
  Convenience wrapper around `/api/v1/general/remove-pages`.
- `stirling_split_pdf`, `stirling_extract_images`, `stirling_ocr_pdf`, `stirling_convert_file`
  Convenience wrappers for split, image extraction, OCR, and common conversions.
- `stirling_add_watermark`, `stirling_add_password`, `stirling_remove_password`
  Convenience wrappers for watermarking and password operations.
- `stirling_repair_pdf`, `stirling_sanitize_pdf`, `stirling_flatten_pdf`
  Convenience wrappers for repair, sanitise, and flatten operations.
- `stirling_extract_pages`, `stirling_crop_pdf`, `stirling_scale_pages`
  Convenience wrappers for selected-page extraction, crop, and page scaling.
- `stirling_redact_pdf`
  Automatic text redaction wrapper. It requires `confirmed=true` because redaction is destructive.
- `stirling_reorganize_pages`, `stirling_overlay_pdfs`, `stirling_page_layout`, `stirling_booklet_pdf`
  Convenience wrappers for page ordering and page composition workflows.
- `stirling_sign_pdf`, `stirling_cert_sign_pdf`
  Visual and certificate signing wrappers. They require `confirmed=true`.
- `stirling_change_metadata`, `stirling_change_permissions`
  Metadata and security-permission wrappers. Permission changes require `confirmed=true`.
- `stirling_remove_certificate_signatures`, `stirling_unlock_pdf_forms`
  Certificate-signature removal and form-unlock wrappers. They require `confirmed=true`.
- `stirling_add_attachments`, `stirling_edit_table_of_contents`
  Convenience wrappers for embedded-file attachments and bookmark/table-of-contents editing.
- `stirling_remove_blank_pages`, `stirling_split_scanned_photos`, `stirling_replace_colors`
  Convenience wrappers for blank-page cleanup, scanned-photo extraction, and color replacement/inversion.

## Live Integration Test

With the Java backend running:

```bash
cd engine
STIRLING_JAVA_BACKEND_URL=http://localhost:8080 python scripts/mcp_live_integration.py
```

The script runs rotate, merge, compress, and remove-pages against `testing/test_pdf_1.pdf`.

## Async Jobs

`stirling_call_endpoint` and the typed endpoint wrappers support `async_job=true`. Add `wait_for_job=true` to poll
until completion and fetch the final result, or call `stirling_get_job_status` later with the returned job id.

## External PDF Tooling

The engine PDF editor workflow uses `pdftohtml`. Install Poppler and ensure `pdftohtml` is on `PATH`; the
`stirling_health_check` tool reports this explicitly.

## Resources

- `stirling://operations/catalog`
- `stirling://mcp/readme`
- `stirling://mcp/workflows`

## Notes

- MCP clients can use JSON-RPC `ping` for a minimal liveness check, and `stirling_health_check` for dependency readiness.
- `scripts/mcp_smoke_test.py` uses the stdio JSON-RPC path: initialize, list tools, run `stirling_health_check`,
  then require the backend rotate probe to pass.
- `stirling_call_endpoint` is intentionally generic so MCP clients can execute backend tools without waiting for a one-tool-per-endpoint wrapper.
- `stirling_get_operation_details` includes the frontend operation hook path and relevant source snippets to help agents construct the correct multipart fields.
