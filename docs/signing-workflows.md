# Signing and E-Signature Workflows

Stirling PDF provides visual signatures, certificate-backed PAdES signatures, recipient e-signature
workflows, validation, trust-store management, signed-revision recovery, and explicit signature-field
removal. The sender workspace is available through **Request Signatures**; all workflow capabilities are
also exposed under `/api/v1/security/e-sign/` and through the engine MCP server.

## Certificate Signing

Certificate signing supports PEM, PKCS12/PFX, JKS, server certificates, PKCS#11, HSM/cloud KMS,
remote signing, and QES adapters. PAdES profiles are B-B, B-T, B-LT, and B-LTA. Timestamped profiles
require a reachable TSA. Long-term profiles embed available OCSP/CRL material and B-LTA adds a document
timestamp. Existing unsigned signature fields can be targeted by name and multiple signatures are saved
incrementally.

Keep private keys outside Stirling whenever a managed signer is available. Restrict PKCS#11 library paths,
rotate API credentials, and test TSA, OCSP, CRL, and trust anchors before production use.

## Recipient Workflows

1. Upload a PDF once in the shared workspace.
2. Add recipients, delivery channels, roles, routing order, authentication, expiry, and reminders.
3. Place recipient-aware fields or provide text anchors through the API.
4. Create a draft, send it, and monitor status in the workflow dashboard.
5. Download the completed PDF and tamper-evident evidence report.

Signing links contain random tokens whose hashes, not plaintext values, are persisted. Access codes are
also hashed. Templates never persist reusable access codes; supply those recipient values when a template
is instantiated. Owner-scoped operations intentionally return not found for another user's resources.

## Email Step-Up Verification

Select `emailOtp` authentication to require fresh email verification after review and consent,
following the eKYC signing sequence. The existing email notification provider must be configured.
The recipient's signing link allows document review; the email code authorizes the signing step.
This is an additional email verification step, not independent identity proofing or a second factor
when the signing link was also delivered to that same mailbox.

`POST /api/v1/security/e-sign/recipients/{token}/otp` accepts the intended signing payload, including
consent and field values, and returns `expiresAt` and `resendAt`. Submit the same payload with `otp`
to the existing `/sign` endpoint. Codes expire after five minutes; resending has a 60-second cooldown.
Five failed attempts lock verification for the remainder of a 15-minute attempt window, including
across resends and restarts. A changed document, field schema, or consent invalidates the challenge.

Challenges contain salted hashes and are saved under `requests/{requestId}/.step-up`, so they are
removed with their workflow. Challenge consumption uses filesystem locks
and atomic replacement where available. Shared-storage deployments must support those semantics.
The code is consumed under the same per-request lock as the signature itself, immediately before
the PDF revision is written. If that write then fails, the workflow records a
`RECIPIENT_SIGNATURE_FAILED` audit event with the reason and `stepUpConsumed=true`, the response
carries the `SIGNATURE_APPLY_FAILED` error code, and the signer must request a fresh code.
This increment adds authorization to recipient signing; it does not itself add a KMS certificate seal
to recipient-generated PDF revisions or connect to the eKYC production n8n deployment.

## Concurrency, Revisions, and Idempotency

Every mutation of one request (`send`, reminders, `viewed`, `otp`, `sign`, `decline`, `cancel`,
`archive`, `delete`, webhook retries, and expiry) runs under a per-request lock keyed by the request id.
Independent requests never wait for each other. The lock has two layers:

- An in-process reentrant lock per request id, shared by every thread of the JVM. This is the fast
  path and the only coordination a single instance needs.
- An advisory file lock on `requests/{requestId}/.lock`, taken after the in-process lock and held
  until the metadata has been replaced. This is what coordinates several instances that share the
  request storage.

The in-process lock serializes threads of one process only. It gives no protection between two
instances, between an instance and an external script, or across a restart. For those, the file
lock must work on the storage in use: it needs a filesystem that honours exclusive byte-range locks
across hosts, such as a local disk, NFSv4 with a working lock manager, or SMB. Many object-storage
gateways and FUSE mounts accept the lock call without enforcing it, or reject it. When the JVM
reports that the file cannot be locked, Stirling logs one warning and continues with the in-process
lock alone; a multi-instance deployment on such storage must route every mutation of a request to a
single instance (for example sticky routing on the request id or token) or run one instance. The
operating system releases a file lock when its process exits, so a stale `.lock` file after a crash
is harmless and is removed with the request. Verify the lock on the target storage before scaling
out: with the request directory shared, a second process must block on `/sign` until the first has
returned.

Each committed change increments the request's `workflowRevision`, returned on every request view.
Mutating calls accept two optional fields, in the JSON body or, for the key, as an
`Idempotency-Key` header:

| Field | Behaviour |
| --- | --- |
| `expectedWorkflowRevision` | The revision the caller last read. A different stored revision is rejected with `409` and code `WORKFLOW_REVISION_STALE`; the body carries `currentWorkflowRevision`. |
| `idempotencyKey` | 1-128 characters of `A-Z a-z 0-9 . _ : -`. The same key with the same action, recipient, and payload replays the stored result with `replayed: true`. The same key with a different action or payload is rejected with `409` and code `IDEMPOTENCY_KEY_REUSED`. |

Fields that are absent behave as before, so existing API, n8n, and MCP callers keep working. Keys
are stored with the request (the last 200) and are removed with it. A replayed response never repeats
signing tokens or signing links, because tokens are delivered exactly once; the request view and
recipient states are complete. Secrets such as `otp` and `accessCode` do not form part of the
payload comparison, so a retry with a fresh code still replays a completed signature.

State changes are validated by one transition table (`ESignatureWorkflowTransitions`) and rejected
with `409` and a stable code when they are not allowed:

| From | To |
| --- | --- |
| `DRAFT` | `SENT`, `CANCELLED`, `EXPIRED` |
| `SENT` | `IN_PROGRESS`, `COMPLETED`, `DECLINED`, `CANCELLED`, `EXPIRED` |
| `IN_PROGRESS` | `COMPLETED`, `DECLINED`, `CANCELLED`, `EXPIRED` |
| `COMPLETED`, `DECLINED`, `CANCELLED`, `EXPIRED` | `ARCHIVED` |
| `ARCHIVED` | none |

Recipients move `PENDING -> SENT -> VIEWED -> SIGNED | DECLINED`, may expire from any non-terminal
state, and never leave `SIGNED`, `DECLINED`, or `EXPIRED`. Re-sending keeps a viewed recipient at
`VIEWED` and an in-progress request at `IN_PROGRESS`. Cancelling a completed, declined, or expired
request is refused; archive it instead.

Problem Details error responses carry a `code` property for these outcomes:
`WORKFLOW_REVISION_STALE`, `IDEMPOTENCY_KEY_REUSED`, `WORKFLOW_STATE_INVALID`,
`RECIPIENT_STATE_INVALID`, `SIGNING_ORDER_BLOCKED`, and `SIGNATURE_APPLY_FAILED`. Expired and
cancelled links keep returning `410`.

The PDF revision and the metadata are still two files replaced one after the other. The lock stops
concurrent writers from interleaving, but a crash between the two writes can leave a PDF revision
that the metadata does not reference; startup recovery for that case is roadmap item 1.4.

## Templates and Automation

Templates persist a validated source PDF and recipient, field, routing, and reminder defaults. The bulk
endpoint accepts at most 500 rows and returns an outcome for every row. Successful rows remain created
when a different row fails. Text anchors use visible PDF text and normalized coordinates; required missing
anchors fail the request clearly.

The OpenAPI endpoints are suitable for n8n and other HTTP automation. An importable n8n example is at
`engine/examples/n8n-esign-bulk-template.json`. First-class MCP tools cover request creation/list/send,
template creation/list/instantiation/bulk dispatch, and event polling. MCP creation and dispatch tools
require `confirmed=true`.

## Storage, Retention, and Scale

Workflow documents and metadata are stored below the configured custom-files path in
`e-signature-workflows/requests`; templates use the sibling `templates` directory. Metadata writes use an
atomic replacement where supported, and each request directory holds a `.lock` file used for the
per-request lock described above. Archived workflows can be permanently deleted. Configure filesystem
encryption, backup, retention, and access controls appropriate to the documents being signed.

Ordinary uploads stream to storage and do not materialize the PDF in memory. The workflow limit is 100 GiB.
Text-anchor discovery and cryptographic PDF operations require parsing the document and therefore need
memory and temporary storage appropriate to the specific PDF. Load-test representative documents before
raising production concurrency.

## Validation and Recovery

Validation reports identify each signed revision, its byte length, whether it covers the current document,
and whether later revisions exist. **Extract signed revision** is the safe default in the signature-removal
tool. It returns the exact historical revision without rewriting signed bytes. Flattening signature fields
rewrites the document, invalidates cryptographic evidence, and requires explicit acknowledgement.

## Migration

Workflow metadata is versioned. Version 0 records are normalized to version 1, legacy plaintext signing
tokens are one-way hashed and removed, and missing collections/authentication records are restored. A
workflow from a newer unsupported model version is rejected to prevent silent data loss. Back up the
workflow directory before upgrading and verify representative active and archived requests after upgrade.

## Interoperability

Before production release, validate output with at least Adobe Acrobat and one independent ETSI-aware
validator. Exercise RSA and ECDSA certificates, each enabled PAdES profile, multiple signatures, existing
fields, TSA failure, revocation material, and an untrusted chain. The evidence report supplements the PDF's
cryptographic signatures; it does not replace certificate-path validation.
