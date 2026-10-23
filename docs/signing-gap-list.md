# Signing and Website Gap List (Phase 0)

Revision assessed: `8d350dfe9` (2026-09-13 eKYC step-up increment). The worktree used for this
review is at `e1584149b`, whose only difference from `8d350dfe9` is documentation and `.gitignore`
(`git diff --stat 8d350dfe9 HEAD` lists three non-code files), so the code below is the `8d350dfe9` code.
Review date: 2026-10-23. Source of the backlog: `docs/website-improvement-plan.md`.

Method: each numbered backlog item was read against the code, not against the docs. Verdicts:

- **Present**: the capability the item describes exists and has been read in code.
- **Partial**: some of it exists; the Gap column says what is missing.
- **Absent**: searched for and not found.
- **Needs verification**: cannot be decided by static reading (needs a browser, a measurement, a user
  study or an external system). Nothing here was run against a live deployment, real device or real
  provider; "Present" never means "measured".

Evidence paths are relative to the repository root. `Service` means
`app/core/src/main/java/stirling/software/SPDF/service/ESignatureWorkflowService.java`.

## Summary

53 items (1.1 to 8.8, including 8.1b): **1 present, 34 partial, 16 absent, 2 needs verification.**

| Workstream | Items | Present | Partial | Absent | Needs verification |
| --- | --- | --- | --- | --- | --- |
| 1. Signing and delivery | 7 | 0 | 5 | 2 | 0 |
| 2. Workspace | 6 | 0 | 5 | 1 | 0 |
| 3. PDF authoring | 6 | 0 | 5 | 1 | 0 |
| 4. Recipient experience | 7 | 0 | 5 | 1 | 1 |
| 5. Performance and scale | 6 | 0 | 4 | 2 | 0 |
| 6. Intelligence | 6 | 0 | 2 | 4 | 0 |
| 7. Visual identity | 6 | 0 | 3 | 2 | 1 |
| 8. Production readiness | 9 | 1 | 5 | 3 | 0 |
| **Total** | **53** | **1** | **34** | **16** | **2** |

Reading the counts: "partial" is the largest group because the 2026-08-12 and 2026-09-13 series
delivered foundations for most items (workflow service, hash-chained audit, webhook outbox,
recipient outcomes, email step-up, saved signatures, FileContext persistence, job queue) rather than
the full behaviour each item specifies. Workstream 1 is the highest-risk area: the signing
mutation has no lock, revision check or idempotency key (1.2, 1.3), and recipient signatures are
visual PDF revisions that are not cryptographically sealed (see Findings). Workstream 8's blocker is
the expired shared certificate fixtures (8.1b), which currently fail 16 of 82 targeted backend tests.

Not included: effort estimates per workstream (the Phase 0 exit criterion also asks for them), the
three journey walk-throughs, the measurement corpus and the topology record. Those remain open Phase 0
items.

## Check results

Run on 2026-10-23 (UTC) in a Claude Code worktree at `e1584149b` (code identical to `8d350dfe9`).
Environment: Windows 11 (10.0.26200), OpenJDK 21.0.2, Gradle 9.3.1 wrapper, Node v24.14.0 (CI uses
Node 22), Git Bash. Nothing was changed to make a check pass.

### Backend: signing tests

Specified command:

```text
./gradlew :stirling-pdf:test --tests '*Signature*' --tests '*Sign*' --tests '*PadesLtv*'
```

**Result of the command as written: could not run (environment).** From the repository root on Windows
Gradle exits in 47 s with `Task 'test_irs_signed.pdf' not found in root project`. The JVM launcher
expands the unquoted `*Sign*` pattern against files in the current directory and the repository root
contains `test_irs_signed.pdf`. This is not a test result.

Equivalent command, run from a directory with no matching file names (same filters, same project):

```text
cd app && ../gradlew -p .. :stirling-pdf:test --tests '*Signature*' --tests '*Sign*' --tests '*PadesLtv*'
```

**Result: failed. 82 tests ran, 66 passed, 16 failed, 0 skipped (BUILD FAILED in 5 m 53 s).**

| Test class | Run | Failed |
| --- | --- | --- |
| `CertSignControllerTest` | 16 | 12 |
| `RemoveCertSignControllerTest` | 3 | 3 |
| `SigningTrustStoreServiceTest` | 2 | 1 |
| `ESignatureWorkflowServiceTest` | 17 | 0 |
| `SignatureServiceTest` | 11 | 0 |
| `SigningOtpServiceTest` | 6 | 0 |
| `ESignatureTemplateServiceTest` | 5 | 0 |
| `KmsSignatureServiceTest` | 4 | 0 |
| `PadesLtvServiceTest` | 3 | 0 |
| `SigningTrustStoreControllerTest`, `SigningModelValidatorTest`, `SigningAnchorServiceTest` | 3 each | 0 |
| `ESignatureWebhookServiceTest`, `ESignatureWorkflowMigrationServiceTest` | 2 each | 0 |
| `ESignaturePdfServiceTest`, `SigningAccessCodeHasherTest` | 1 each | 0 |

Failure classification: 15 failures are `java.security.cert.CertificateExpiredException` from the shared
fixtures under `app/core/src/test/resources/certs/` (valid until 2026-08-26): 11 in `CertSignControllerTest`,
3 in `RemoveCertSignControllerTest`, 1 in `SigningTrustStoreServiceTest` (wrapped in `IOException`).
The sixteenth, `CertSignControllerTest.testVisibleSignatureRejectsMissingPage`, is an assertion failure
(expected message not contained) using the same expired `test-cert.p12`; the exception text was not
captured, so attributing it to the expiry is probable but not confirmed. No failure was found in
application code, but no failing test was re-run against a corrected fixture, so "fixture only" is a
classification of the 15 clear cases, not a reproduction. The workflow, OTP, template, webhook, PAdES
(`PadesLtvServiceTest`), KMS and trust-store-controller tests all passed.

### Frontend: core typecheck

Specified command:

```text
cd frontend && npm run typecheck:core
```

**Result as first run: failed (environment).** A fresh worktree had no `node_modules`, so `npm ci` was
run first (exit 0). Then `tsc --noEmit --project src/core/tsconfig.json` exited 2 with a single error,
`LocalIcon.tsx(3,21): TS2307 Cannot find module '../../../assets/material-symbols-icons.json'`. That file is
generated and git-ignored; CI produces it with `npm run prep` before type-checking.

After `npm run generate-icons` (the icon step of `prep`, which writes only the ignored files
`src/assets/material-symbols-icons.json` and `.d.ts`):

```text
cd frontend && npm run generate-icons && npm run typecheck:core
```

**Result: passed (exit 0, no diagnostics; 39 s).** The proprietary, saas, desktop and scripts layers
(`typecheck:all`), lint and `vitest` were not run in this review.

Not run, and not claimed: Playwright (`frontend/src/tests/recipient-signing.spec.ts`), engine `make check`,
full `./gradlew :stirling-pdf:test`, any live backend, browser or provider.

## Findings outside the backlog

Observed while reading the code; none of these is fixed here, and items marked "unconfirmed" were
found by reading only.

- **Recipient revisions are not cryptographically sealed.** `ESignatureWorkflowService.sign` calls only
  `ESignaturePdfService.applyRecipientFields`, which draws text or an image into an incremental PDF
  revision. Nothing in the e-signature path calls `PadesLtvService`, `KmsSignatureService` or the
  certificate-sign controller, so the PAdES/KMS work from the 2026-08-12 series is available through
  the certificate-sign tool but is not applied to recipient signatures. This matches the plan's
  "still required" list; it is why 1.7, 4.6 and 8.4 are Partial at best.
- **OTP locking is stronger than the plan says.** `SigningOtpService.locked` takes a striped
  in-process monitor and an OS `FileChannel` lock per challenge file, and
  `SigningOtpServiceTest.permitsExactlyOneConcurrentConsumptionAcrossInstances` uses two service
  instances. It is therefore coordinated across processes that share a filesystem, provided that
  filesystem honours file locks (the docs already say so). The signing mutation itself has no such lock.
- **Legacy token migration (unconfirmed).** `ESignatureWorkflowMigrationService.migrateLegacyTokens`
  stores a lowercase hex SHA-256, while `ESignatureWorkflowService.hashToken`/`tokenMatches` compare
  against an unpadded base64url SHA-256. If so, a migrated version-0 token no longer resolves.
  `ESignatureWorkflowMigrationServiceTest` only asserts that a hash is present and that the plaintext
  is gone. Needs a test that resolves a migrated token.
- **Recipient token handling.** `createNotification` always issues a fresh token and replaces the stored
  hash, ignoring its `rotateToken` parameter, so every reminder silently invalidates the previous link.
- **Retention is recorded, not enforced.** `retentionUntil` is set to now plus 365 days at creation and
  returned in the view; no code reads it to purge or lock a workflow.
- **Read paths mutate.** `loadWorkflow` and `listWorkflows` rewrite `metadata.json` when a request has
  expired, so concurrent readers can race writers (see 1.3).
- **Scale shape.** `resolveToken`, `listRequests`, `listEvents` and the due-reminder and webhook sweeps
  each read and migrate every `metadata.json` under the requests directory (see 5.2).
- **Usage metrics may carry signing tokens (unconfirmed).** `MetricsFilter` tags its `http.requests`
  counter with the raw request URI, which includes recipient tokens on the public e-sign endpoints;
  whether `MetricsAggregatorService` exports those paths to PostHog depends on configuration and was
  not run.
- **Plan wording to correct.** The plan names `CertSignControllerTest` and `RemoveCertSignControllerTest`
  as the tests loading the expired fixture; `SigningTrustStoreServiceTest` also loads
  `certs/test-cert.der` and fails.

## 1. Dependable Signing and Delivery

| Item | Verdict | Evidence | Gap |
| --- | --- | --- | --- |
| 1.1 Reproduce concurrency and retry behavior | Absent | `ESignatureWorkflowServiceTest` (17 tests) has no concurrent-recipient, repeated-submission, cancel-during-sign or reminder-during-sign case. The only concurrency test is `SigningOtpServiceTest.permitsExactlyOneConcurrentConsumptionAcrossInstances`. | Write the four reproductions and assert PDF bytes, recipient states and audit order together. By reading `Service.sign` (load, mutate, `saveWorkflow` with no lock) two simultaneous signers are expected to lose one update; this has not been reproduced. |
| 1.2 Define workflow transitions | Partial | Guards exist but are scattered: `ensureWorkflowCanContinue`, `ensureRecipientCanAct`, `isActiveWorkflow`, `isRecipientInActiveOrder`, and a second `Recipient has already signed` 409 in `sign`. `ESignatureWorkflow.WorkflowStatus` is a plain enum. No `If-Match`, expected-revision or idempotency-key handling in `ESignatureWorkflowController` or `ESignatureSignRequest`. | No central transition table; no expected workflow revision; no idempotency key or stored-result replay (a retry after a lost response gets 409, not the original result). The OTP challenge is consumed (`SigningOtpService.consume`) before `pdfService.applyRecipientFields`, as the plan says. |
| 1.3 Serialize document updates per request | Absent | `Service` has no `synchronized`, lock or `@Transactional`; every mutator does `loadWorkflow` then `saveWorkflow` (`saveWorkflow` is an atomic file replace only). Read paths also write: `loadWorkflow`/`listWorkflows` call `expireIfNeeded`, and `processDueWebhooks` saves a stale copy after HTTP calls. | Per-request lock that works across instances (the OTP code already uses a `FileChannel` lock on shared storage, see Findings), applied to sign/decline/cancel/send/reminder/webhook/expiry paths. Document the supported topology. |
| 1.4 Make PDF and metadata publication recoverable | Partial | `ESignaturePdfService.applyRecipientFields` writes an incremental revision to a temp file and `replaceAtomically` over `document.pdf`; `before`/`afterSha256` are recorded in the `DOCUMENT_UPDATED` audit event; `saveWorkflow` replaces `metadata.json` atomically. | The PDF is overwritten in place, not kept as immutable revisions, and PDF and metadata are two separate publications. A crash between them leaves a revised PDF with the old `documentRevision`, audit and recipient state (and a consumed OTP). No journal, startup recovery, hash verification on load or orphan cleanup (`metadata-*.tmp`, `document-revision-*.pdf`). |
| 1.5 Persist delivery work before dispatch | Partial | Webhook outbox: `enqueueWebhook` stores a `WebhookDelivery` inside the workflow file in the same atomic save as the audit event; `processWebhookOutbox` (`@Scheduled`, 60 s), `webhookRetryDelay` (1 min to 48 h), `MAX_WEBHOOK_ATTEMPTS = 8`, `WebhookDeliveryStatus.FAILED`, `retryWebhooks` endpoint, stable `X-Stirling-Event-Id`. | Email/SMS is not in the outbox: `createNotification` calls `dispatchNotification` inline (up to 3 immediate attempts, no backoff) while handling the sign/send request; a failure is only audited as `NOTIFICATION_DELIVERY_FAILED` and returned in the response. No jitter, worker leases (two processors can send the same delivery) or dead-letter view beyond `FAILED`. |
| 1.6 Complete the copy policy | Partial | `createCompletionNotifications` sends the requester a completion notice and each `CC` recipient a tokenised download link; `RecipientSigningPage` distinguishes "your step is complete" from "all required recipients have completed" (`recipientSigning.waitingForOthers`/`allCompleted`). Links are absolute when `publicBaseUrl` is set; `ESignatureWorkflowController.createRequest` fills it from the request's forwarded headers when the sender omits it. | Signers and approvers never receive the completed copy; no explicit per-request copy settings. Without a base URL the link is relative. Access-code recipients rely on the sender passing the code out of band; no delivery of it is modelled. |
| 1.7 Harden audit claims | Partial | Hash-chained `AuditEvent` (`addAudit`, `verifyAuditIntegrity`), server `Instant.now()` timestamps, IP and user agent captured, `DOCUMENT_UPDATED` records revision and before/after hashes, `getEvidence` returns the current document SHA-256. | Consent text is stored per recipient but not versioned or hashed into the audit event. `getEvidence` does not compare the current file hash with the last committed `afterSha256`. The chain is self-contained, so someone with file access can rewrite it consistently; no external anchor. `ESignatureWorkflowController.clientIp` trusts the first `X-Forwarded-For` value unconditionally. No statement that provider acceptance is not receipt. |

## 2. One Coherent Document Workspace

Paths in this section are under `frontend/src/core/`.

| Item | Verdict | Evidence | Gap |
| --- | --- | --- | --- |
| 2.1 Inventory cross-tool state | Absent | No inventory document exists in `docs/`. State owners are scattered: `contexts/FileContext.tsx` and `types/fileContext.ts` (selected files and pages), `contexts/ViewerContext.tsx` (zoom, scroll via bridges), per-tool hook state. | Write the map. |
| 2.2 Workspace session contract | Partial | `types/fileContext.ts` declares `ViewerConfig` and `FileEditHistory` but nothing else references them. FileContext state holds file stubs; bytes live in IndexedDB (`services/fileStorage.ts`), so large PDFs are already out of React state. | No per-document view-state record (zoom and page are not keyed by file); the `DRAFTS` database in `services/indexedDBManager.ts` is defined and never used; no session contract. |
| 2.3 Extend existing history | Partial | `hooks/tools/shared/useToolOperation.ts` `undoOperation` (single step, last operation, `UNDO_CONSUME_FILES` in `contexts/file/fileActions.ts`); `UndoManager` in `components/pageEditor/commands/pageCommands.ts`; `components/viewer/HistoryAPIBridge.tsx` for annotations; version lineage in `utils/fileHistoryUtils.ts`. | No workspace-wide, bounded multi-step history; no drag grouping or marking of irreversible operations in the page-editor stack; no recovery of a prior signed revision as a separate document. |
| 2.4 Revision review | Partial | Version groups listed in `components/fileManager/FileHistoryGroup.tsx` and `FileListItem.tsx`; a manual two-file compare tool in `components/tools/compare/`. | The "Restore" menu item in `FileListItem.tsx` only calls `stopPropagation` (no behaviour); no restore-as-copy; compare is not tied to revisions (synchronised view not checked); no "document being exported" indicator found. |
| 2.5 Session recovery | Partial | IndexedDB schema versioning and one history-field migration (`services/indexedDBManager.ts`), storage estimate (`services/fileStorage.ts`), `QuotaExceededError` handling (`useToolOperation.ts`, `services/processingErrorHandler.ts`), dirty tracking (`contexts/UnsavedChangesContext.tsx`). | No autosave or debounce; `FileContext.tsx` has "Persistence loading disabled - files only loaded on explicit user action"; no corrupt-record handling, retention/privacy policy or exclusion of signing secrets. |
| 2.6 Unify operation feedback | Partial | `services/jobClient.ts` `submitAndWaitForJob` with status callbacks; `useToolOperation` exposes status, progress and `cancelOperation`; axios cancel in `hooks/tools/shared/useToolApiCalls.ts`; shared toasts in `components/toast/`. | `jobClient.ts` has no job-cancel call and the async path in `useToolOperation` passes no cancel token, so Cancel may not stop polling or the server job (read from code, unconfirmed). No "cancelled" state, no global operation registry, job status strings untranslated. Whether tools can be switched during a background operation needs a browser check. |

## 3. Precise PDF Authoring

| Item | Verdict | Evidence | Gap |
| --- | --- | --- | --- |
| 3.1 Stabilize geometry | Partial | Fields use normalized 0 to 1 top-left bounds (`frontend/src/core/types/signing.ts`, `isNormalizedSigningBounds`; Java `SigningModelValidator`). `ESignaturePdfService.applyField` maps them onto the crop box with a y flip; `SigningAnchorService` also uses the crop box. | Page rotation is never read (`getRotation` appears nowhere in the signing code), so rotated pages are expected to place fields wrongly. `ESignaturePdfServiceTest` has one test; no rotated, cropped or mixed-size fixtures and no one-point tolerance check. |
| 3.2 Selection and field inspector | Partial | Recipient assignment, required and read-only flags exist in the model; `SigningFieldPageOverlay.placeField` creates fields with fixed defaults (`required: true`, `readOnly: false`, label from type). | `SigningFieldAuthoringContext` has no selection state, no inspector, no multi-select, no numeric position/size controls and no per-field settings UI. `radio`/`dropdown` are in `SigningFieldType` but cannot be authored (no options editor, not in the palette in `RequestSignatures.tsx`). |
| 3.3 Improve placement | Absent | `SigningFieldPageOverlay` uses `Rnd` with `bounds="parent"` only. | No snapping, guides, distribution, duplicate/copy/paste, multi-move or collision/out-of-page feedback (positions are clamped silently). |
| 3.4 Keyboard and touch operations | Partial | Each field is focusable (`tabIndex={0}`) and `Delete`/`Backspace` removes it (`SigningFieldPageOverlay`, covered by `SigningFieldPageOverlay.test.tsx`). | No arrow-key move or resize, selection navigation, Escape/cancel or undoable delete. The overlay sets `touchAction: 'none'` over the whole page while authoring, which conflicts with the "preserve scrolling and pinch zoom" requirement. |
| 3.5 Reusable appearances | Partial | The Sign tool has saved typed/drawn/image signatures with user and shared scope and limits (`app/proprietary/.../SignatureController.java`, `SignatureService`: 20 per user, 2 MB each, 20 MB total; `frontend/.../tools/sign/SavedSignaturesSection.tsx`). `ESignaturePdfService.drawSignatureImage` accepts PNG/JPEG data URLs up to 5 MB. | The e-sign recipient path only ever sends `signatureType: 'typed'` (`RecipientSigningPage.handleSign`); the drawn and image paths are unreachable from the UI and there is no appearance preview. Saved variants are not offered to recipients. |
| 3.6 Sender preflight | Partial | `RequestSignatures.tsx` runs `validateSigningModel` plus email, phone and access-code checks before enabling "Create draft"; the backend rejects a send when a signer has no field (`ensureSignerFieldsAssigned`) and bounds outside the page (`SigningModelValidator`). | No check for approvers/unassigned fields in the UI, invisible or overlapping placements, or signed-document restrictions, and no recipient preview before send. |

## 4. Effortless Recipient Experience

| Item | Verdict | Evidence | Gap |
| --- | --- | --- | --- |
| 4.1 Explicit journey states | Partial | `RecipientSigningPage.tsx` has loading, access-code gate, review/filling, submitting, signed, declined and closed branches (`outcome`, `closed`, `authenticated`), and `RecipientSigningPage.test.tsx` covers several. | States are implicit flags, not a model. Expired and cancelled requests fail at `resolveToken` with HTTP 410 and are shown as the generic "unavailable" error, so the dedicated `closed` message is unreachable for them. No distinct locked state (a lockout shows "access code is incorrect"). |
| 4.2 Integrate document rendering | Absent | The page renders `<iframe src={blobUrl}>` (`RecipientSigningPage.tsx`) after downloading the whole PDF as a blob (`downloadRecipientDocument`). | The established viewer, recipient-aware field overlays and range loading are not used; mobile native-PDF behaviour is unconfirmed. |
| 4.3 Guide field completion | Partial | Progress text and bar, "Next required field" focuses the first incomplete input, type-specific controls (`RecipientField`), required checks. | No highlighting of the field on the page, no inline error messages, and the date default is the browser's `new Date().toLocaleDateString()` (`initialFieldValues`) rather than a server-derived value; the server only supplies a date when the field is blank. |
| 4.4 Complete signing choices | Partial | Consent is a separate checkbox from field values; `RecipientSignInput` allows `'typed' \| 'drawn'` with `signatureDataUrl`. | Only typed is exposed in the UI; no draw/upload mode and no appearance review before accepting. |
| 4.5 Handle interrupted sessions | Partial | A signed response followed by a failed PDF refresh is shown as "Your signature was recorded, but the updated PDF could not be downloaded" with a retry (`RecipientSigningPage.tsx`, tested). | No draft persistence, no unsent/submitting recovery after reload, no idempotent retry (see 1.2). A repeated tap is blocked client-side by `submitting`; the server would answer 409 on the second call. |
| 4.6 Completion and exceptions | Partial | Signed (waiting for others or all complete), declined and closed outcomes with distinct text (`b90965824`). | No statement of when/where the final document will be available, no expiry or lockout outcome (see 4.1), and nothing separating "signature recorded" from cryptographic validity (recipient revisions are visual, see Findings). |
| 4.7 Verify mobile accessibility | Needs verification | `role="status"`/`role="alert"` regions, a focus-movement test, and a Chromium run at 390 px in `frontend/src/tests/recipient-signing.spec.ts` with mocked APIs. | No screen-reader, virtual-keyboard, pinch-zoom, landscape or long-label evidence; only Chromium is configured active for mobile widths. |

## 5. Predictable Performance and Scale

Paths prefixed `core/` are under `app/core/src/main/java/stirling/software/SPDF/`; `common/` under
`app/common/src/main/java/stirling/software/common/`.

| Item | Verdict | Evidence | Gap |
| --- | --- | --- | --- |
| 5.1 Instrument before optimizing | Partial | `core/config/MetricsFilter.java` registers one Micrometer counter, `http.requests`, tagged with method, raw request URI and session id; `core/config/MetricsConfig.java` filters out other meters; `frontend/src/reportWebVitals.js` exists. | No timers, queue-delay, heap, disk or browser-memory measurement and no tracing; `reportWebVitals.js` is not imported (per survey grep). The URI tag is the raw path, so recipient paths such as `/api/v1/security/e-sign/recipients/{token}/sign` become tag values; whether they reach the usage-metrics export (`core/service/MetricsAggregatorService.java`) needs a runtime check. Token redaction is therefore absent. Unbounded tag cardinality (session id and URI). |
| 5.2 Index workflow queries | Absent | `Service.resolveToken`, `listRequests`, `listEvents`, `getDueReminders`, `sendDueReminders` and `processDueWebhooks` each call `listWorkflows`, which reads and migrates every `metadata.json`. | Indexed token-hash and owner/status/time lookup, migration of existing records, rebuild/recovery. Persistence choice must be settled with 1.3 first. |
| 5.3 Paginate dashboards and events | Partial | Filtering only: `ESignatureWorkflowController.listRequests` takes `status`; `listEvents` takes `since` and `type`. `SigningWorkflowDashboard.tsx` renders the full list. | No cursor or limit parameters, bounded responses, incremental loading or virtualization in the signing UI (`@tanstack/react-virtual` is used only in `components/pageEditor/DragDropGrid.tsx`). Pageable APIs exist only for the proprietary audit controllers. |
| 5.4 Bound document processing resources | Partial | `common/service/CustomPDFDocumentFactory.java` (50 MB threshold, temp-file memory modes, concurrency semaphore), `common/util/TempFileManager.java`, `common/service/TempFileCleanupService.java`, streamed downloads in `ESignatureWorkflowController.streamPdf`, `frontend/src/core/services/pdfWorkerManager.ts` (worker limit, `destroyDocument`). | No published operation-specific limits (the upload ceiling is `MAX_DOCUMENT_BYTES` = 100 GiB in `Service`). `SigningAnchorService.resolve(byte[])` is fed by `file.getBytes()` in `Service.createRequest`. No `HttpRange` handling in `app/**/src/main/java`; whether Spring serves ranges for `InputStreamResource` needs verification. The frontend mostly sets `disableStream: true`, and bounded caches and handle leaks across tool switches were not confirmed. |
| 5.5 Strengthen job execution | Partial | `common/service/JobExecutorService.java`, `JobQueue.java` (resource-weighted queue with dynamic capacity), `ResourceMonitor.java` (CPU and memory), `TaskManager.java`, `core/.../common/controller/JobController.cancelJob` (owner-checked). | FIFO queue, no fair scheduling; no temporary-disk admission check; `cancelJob` removes a queued job or marks a running one as errored without cooperative checkpoints; no replay policy; no progress reporting found in the job services. |
| 5.6 Benchmark and tune hotspots | Absent | No JMH, k6 or load scripts, benchmark documents or before/after results in the repository. | Depends on 5.1 and a versioned corpus. |

## 6. Useful, Reviewable Intelligence

| Item | Verdict | Evidence | Gap |
| --- | --- | --- | --- |
| 6.1 Deterministic assistance | Partial | Adjacent pieces only: `FormFillController` (`POST /api/v1/form/fields-with-coordinates`) lists AcroForm fields with coordinates, used by the form-fill tool (`frontend/src/core/tools/formFill/formApi.ts`); `SigningAnchorService` resolves caller-supplied literal text to fields; `SigningModelValidator` validates. | Nothing detects fields or labels for signing, suggests recipient/field mappings or reports incomplete assignments; the form-field endpoint is not connected to `SigningField`, and `RequestSignatures.tsx` has no detect, suggest or anchor UI (anchors are API/MCP only). |
| 6.2 Optional document understanding | Absent | The engine's OCR appears only inside its editing chat agent (`engine/src/editing/`) and MCP tool arguments. | No scanned-document path returning coordinates and source-page evidence for signing suggestions. |
| 6.3 Review interface | Absent | No suggestion-review component in `frontend/src/core/components/signing/` or `RequestSignatures.tsx`. | Whole item; also depends on undoable authoring actions (3.4). |
| 6.4 Contextual next actions | Absent | `frontend/src/core/hooks/useSuggestedTools.ts` shows a fixed tool list; `SigningWorkflowDashboard.tsx` offers manual remind, cancel and archive by status. | State-based suggestions (missing field, unsigned revision). |
| 6.5 Data and execution boundaries | Partial | MCP creation and dispatch require `confirmed=true` (`engine/src/mcp_support.py`: `ESignCreateRequestArgs`, `ESignSendRequestArgs`); the engine can be pointed at a local model through `STIRLING_OPENAI_BASE_URL` (`engine/src/config.py`). | The engine calls external model providers and requires a PostHog key wired into every chat model (`config.py`); no Java-side opt-in setting or deployment policy found; no handling of document text as untrusted input. What is sent to PostHog needs verification. |
| 6.6 Evaluate on a held-out corpus | Absent | `engine/tests/` holds unit tests only; `SigningAnchorServiceTest` is functional. | Corpus, harness and metrics; no quality claim is supportable. |

## 7. Consistent Visual Identity and Interaction

Paths in this section are under `frontend/src/core/` unless stated.

| Item | Verdict | Evidence | Gap |
| --- | --- | --- | --- |
| 7.1 Audit real screens | Absent | No audit document, screenshot set or capture script in `docs/` or `frontend/scripts/`; the only Playwright spec is the functional `frontend/src/tests/recipient-signing.spec.ts`. | Capture the listed screens at desktop and mobile widths. |
| 7.2 Consolidate design tokens | Partial | `theme/mantineTheme.ts` (`createTheme`) maps colours, spacing, radius and shadows to the CSS variables in `styles/theme.css` (roughly 478 custom properties). | No type-scale, line-height, focus-ring, motion or density tokens; the theme sets no `fontSizes`, `headings` or `respectReducedMotion`; hard-coded values remain (for example `transition: 'all 0.2s ease'`); no token documentation. |
| 7.3 Standardize common controls | Partial | `components/shared/LocalIcon.tsx` is used in 52 files; shared `components/toast/`, `ErrorBoundary.tsx`, `SkeletonLoader.tsx`. | 94 files still import `@mui/icons-material` directly (the recipient page does too); no toolbar-dimension tokens, shared confirmation component or button-hierarchy rule found. Visual consistency of the controls themselves needs a browser review. |
| 7.4 Improve layout and hierarchy | Needs verification | Responsive structure exists (`useIsMobile` in 10 files, `components/fileManager/MobileLayout.tsx` and `DesktopLayout.tsx`). | Prominence, panel behaviour at supported widths and 200% zoom, and dashboard scannability can only be judged in a browser. |
| 7.5 Complete feedback states | Partial | Reduced-motion media queries in 4 CSS files (`components/fileEditor/FileEditor.module.css`, `components/tools/ToolPanel.css`, `components/viewer/AttachmentSidebar.css`, `BookmarkSidebar.css`) and a `matchMedia` check in `FullscreenToolSurface.tsx`; `SkeletonLoader` used in 3 files; progress from `useToolOperation`; toasts. | Reduced motion is not global; live-region roles appear only in `pages/RecipientSigningPage.tsx`; progress accuracy, stable loading layouts, empty states and error specificity were not audited. |
| 7.6 Validate with users | Absent | No usability study, journey baseline or session-recording artefact in the repository. | Whole item (needs participants). |

## 8. Demonstrated Production Readiness

| Item | Verdict | Evidence | Gap |
| --- | --- | --- | --- |
| 8.1 Repair the PAdES test fixture | Present | `PadesLtvServiceTest` generates its certificate at runtime; 3 of 3 tests passed in the 2026-10-23 run (see Check results). | None for this item. |
| 8.1b Repair the shared certificate fixtures | Absent | `app/core/src/test/resources/certs/test-cert.*` expired on 2026-08-26 (`openssl x509 -dates` on `test-cert.pem`: notAfter Aug 26 2026). 16 of 82 targeted tests fail on 2026-10-23: 11 in `CertSignControllerTest`, 3 in `RemoveCertSignControllerTest`, 1 in `SigningTrustStoreServiceTest`, all `CertificateExpiredException`, plus `CertSignControllerTest.testVisibleSignatureRejectsMissingPage` (assertion failure on a message, same fixture, cause not captured). | Generate certificates at test time or inject a clock; keep explicit expired/not-yet-valid cases. The scope is wider than the plan states: the `.pem`, `.der`, `.crt`, `.cer`, `.jks`, `.pfx` variants and `SigningTrustStoreServiceTest` are affected too. |
| 8.2 Browser workflow gates | Partial | `frontend/src/tests/recipient-signing.spec.ts`: 4 scenarios (email link and email OTP at 1440 px and 390 px) with mocked recipient endpoints, asserting one sign call. | Not a sender-to-final-PDF journey, no real backend, no routing/cancel/expiry/failure scenarios, no provider doubles. No Playwright step exists under `.github/workflows`, so nothing runs in CI. Firefox and WebKit projects exist in `playwright.config.ts` but were not run. |
| 8.3 Accessibility verification | Absent | No `axe`/`jest-axe`/`jsx-a11y` dependency or config in `frontend/package.json` or `eslint.config.mjs`; no recorded manual results. | Automated scan, keyboard and screen-reader exercises and a WCAG 2.2 AA record. |
| 8.4 PDF interoperability evidence | Partial | `PadesLtvServiceTest`, `CertSignControllerTest.testLocalSignatureUsesPadesBaselineAttributes`, `KmsSignatureServiceTest` (4 tests) exercise profile construction and the KMS contract at unit level. | No independent validator (Adobe, EU DSS, etc.), no ECDSA case (no EC fixture in tests), no revocation or trust-path failure evidence; the cert-sign controller tests currently fail on the expired fixture (8.1b). |
| 8.5 Validate security boundaries | Partial | Owner scoping (`ensureOwnedBy`, test `scopesAuthenticatedSenderOperationsToWorkflowOwner`), access-code lockout (`locksRecipientAuthenticationAfterFiveFailures`), hashed tokens and codes, SSRF check on callbacks (`ESignatureWebhookService`, tested), path checks (`verifyWithin`, `validateSafeId`), PDF content validation (`validatePdfFile`). | Unconditional trust of `X-Forwarded-For`/`-Host`/`-Proto` in `ESignatureWorkflowController`; recipient token has no lifetime beyond optional workflow `expiresAt` (a null expiry never expires) and is regenerated on every send and reminder (`createNotification` ignores its `rotateToken` argument); no request throttling found on the public `/e-sign/recipients/{token}` endpoints (only the per-recipient access-code lockout); no secrets-in-logs or upload-abuse review has been recorded. |
| 8.6 Scale and recovery exercises | Absent | No scale, restart, outage, quota or backup-restore test or script found in `app/core/src/test`, `testing/` or `docs/`. | Whole item; `docs/signing-release-gates.md` lists the gate but no result exists. |
| 8.7 Wire evidence into CI and releases | Partial | `.github/workflows/build.yml` runs `./gradlew build -PnoSpotless` (includes unit tests), `npm run prep && npm run typecheck:all`, lint, build and `npm run test -- --run`; `.github/workflows/ai-engine.yml` runs the engine `make` lint/typecheck/test. | No Playwright, interoperability, performance or scale job; no artifact upload of browser traces; no promotion gate tied to `docs/signing-release-gates.md`. With the expired fixtures, the backend job would be expected to fail on those tests (not observed in CI). |
| 8.8 Operator documentation | Partial | `docs/signing-workflows.md` (certificate signing, workflows, step-up, templates, storage, migration, interoperability) and `docs/signing-release-gates.md`; `engine/MCP.md`. | No provider configuration walk-through, supported topology, capacity, retention enforcement (`retentionUntil` is set but never read by any purge), backup/restore, rollback constraints, alerts, incident procedures or gate owners. |
