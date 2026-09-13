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
Consumption happens before applying the signature; a failed downstream operation requires a fresh code.
This increment adds authorization to recipient signing; it does not itself add a KMS certificate seal
to recipient-generated PDF revisions or connect to the eKYC production n8n deployment.

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
atomic replacement where supported. Archived workflows can be permanently deleted. Configure filesystem
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
