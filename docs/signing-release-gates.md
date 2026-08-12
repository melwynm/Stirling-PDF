# Signing Release Gates

A signing release is ready only when every applicable row is green and the result is recorded in the release
or pull request. Dependency-gated cases may be marked not applicable only with an owner and explanation.

| Gate | Required coverage |
| --- | --- |
| Backend | All `*Signature*`, `*Sign*`, `PadesLtvServiceTest`, trust-store, template, anchor, migration, webhook, PDF service, and workflow tests |
| Frontend | Core typecheck and all signing, certificate-sign, validation, template, dashboard, recipient, overlay, and removal tests |
| MCP | Ruff, Pyright, `test_mcp_support.py`, and `test_mcp_server.py` |
| Translation | TOML syntax and structure tests; new source strings exist in `en-GB` |
| Security | Cross-owner denial, token/access-code hashing, lockout, callback SSRF controls, upload/path validation, destructive confirmation, and secret-free logs/metadata |
| Interoperability | RSA/ECDSA; PAdES B-B/B-T/B-LT/B-LTA; Adobe plus an independent validator; multiple signatures and existing fields |
| End to end | Sender draft to delivery, mobile recipient view/auth/sign, ordered next recipient, completion copies, webhook, completed PDF, evidence download |
| Accessibility | Keyboard-only sender and recipient flows, visible focus, labelled controls, error announcements, 200% zoom, and mobile viewport |
| Scale | Streaming upload regression, representative large PDF, 500-row bulk ceiling, concurrent signing, webhook retry, and storage exhaustion behavior |
| Migration | Version-0 fixture migration, plaintext-token removal, null recovery, future-version rejection, and backup/restore rehearsal |
| Documentation | Operator guide, API/OpenAPI, MCP guide, n8n example, configuration, retention, recovery, and known limits are current |

Recommended local commands:

```text
./gradlew :stirling-pdf:test
cd frontend && pnpm typecheck:core && pnpm test:run
cd engine && make check
```

On Windows, run the engine equivalents with `uv run` and use a repository-local pytest `--basetemp` when
the shared Windows temporary directory is unavailable. Run Playwright against a live backend/frontend pair
for the end-to-end and mobile viewport gates.
