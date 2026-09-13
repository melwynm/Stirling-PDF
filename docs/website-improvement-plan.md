# Website Improvement Plan

Planning date: 2026-09-12

Implementation started: 2026-09-13. Signing reference: `C:\Github\ekyc`.

### eKYC Reference Mapping

The reference uses review/intent/consent, email OTP, server-side OTP verification, n8n/AWS KMS
signing, and a separate result/audit check. Adapt these contracts to Stirling's existing backend,
FileContext, notification providers, and PAdES implementation. Do not copy deployment credentials,
Supabase tenant assumptions, or client-supplied document hashes as authoritative values.

- [x] Read eKYC signing UI, API bridge, and operator documentation.
- [x] Add request-bound, expiring step-up verification with replay and resend protection.
- [ ] Bind verified consent to the exact document revision on the server.
- [ ] Integrate configured remote KMS signing after verification and validate the PDF result.
- [ ] Distinguish signature recorded, cryptographic validity, trust, and audit-chain status in the UI.
- [ ] Verify the adapted flow with local provider doubles; record live-provider checks separately.

The eKYC `verify_document` action reports signature-record existence and audit-chain status;
this alone is not cryptographic verification of PDF bytes. Stirling must retain actual PDF validation.

Objective: make Stirling PDF dependable, coherent, precise, accessible, and fast across its
document workflows. Deliver working improvements in small, independently verifiable increments.

## Starting Point and Status

- The last remote check found local `main` and `origin/main` at `032af038d`.
- The owner reports additional completed signing work. Its revision has not yet been located.
  Reconcile that work before implementing any signing-related changes below.
- This is a proposed implementation backlog, not a declaration that every listed capability is absent.
- Existing foundations include FileContext/IndexedDB persistence, operation undo and cancellation,
  recipient-aware draggable fields, workflow dashboards, templates, signing adapters, and MCP tools.
- The most recent targeted backend run completed 33 tests: 30 passed and three failed because a
  PAdES test certificate had expired. That result does not establish a production certificate defect.
- Browser appearance, real provider behavior, concurrency, and large-file operation remain to be
  measured against the reconciled revision.

Status legend: `[ ]` pending; `[x]` completed with evidence. Record active work, implementation
revision, test result, and remaining limitations in the delivery ledger at the end of this document.

## Phase 0: Establish the Correct Baseline

- [ ] Locate the owner's completed signing revision, fetch it, and compare it with current `main`.
  Preserve existing work and map each backlog task to present, partial, absent, or needs verification.
- [ ] Read applicable repository instructions and identify changes to APIs, metadata, and UI flows.
- [ ] Run the relevant existing checks and record exact commands, environment, revision, and failures.
  Separate fixture/environment failures from application failures through reproduction.
- [ ] Exercise three representative journeys: edit/export a PDF, prepare/send a signing request,
  and complete a recipient response on mobile.
- [ ] Establish reproducible measurements using a versioned synthetic PDF corpus, fixed hardware,
  known network conditions, and small/medium/large workflow datasets.
- [ ] Record the supported deployment topology: single instance, multiple instances, shared storage,
  reverse proxy, browser targets, configured signing providers, and retention expectations.

Exit criterion: a reconciled revision and an evidence-backed gap list. Do not reimplement capabilities
already supplied by the owner's signing work.

## 1. Dependable Signing and Delivery

Outcome: accepted signatures survive retries, simultaneous actions, interrupted processing, and
temporary delivery failures without losing document revisions or misrepresenting completion.

### Implementation Sequence

- [ ] **1.1 Reproduce concurrency and retry behavior.** Test two recipients signing the same request,
  repeated submission of one action, signing during cancellation/expiry, and reminder workers acting
  during a signature. Check PDF content, recipient states, and audit ordering together.
- [ ] **1.2 Define workflow transitions.** Centralize allowed state transitions and revision checks.
  Give every mutating action an expected workflow revision and an idempotency key; replay the stored
  result for an identical completed action and reject conflicting reuse.
- [ ] **1.3 Serialize document updates per request.** Use the reconciled persistence mechanism's
  transaction/locking facilities. A single-process lock is insufficient for multiple instances;
  document and test the coordination supported by the deployment. Keep independent requests concurrent.
- [ ] **1.4 Make PDF and metadata publication recoverable.** Write immutable PDF revisions, verify
  their hashes, and atomically publish the metadata reference and audit event through a transaction
  or recoverable journal. Define startup recovery for interrupted writes and cleanup of orphan files.
- [ ] **1.5 Persist delivery work before dispatch.** Use a durable outbox committed with the workflow
  transition. Add retry scheduling with backoff/jitter, worker leases, attempt limits, dead-letter
  handling, and visible failure status. Avoid holding the signing transaction open during email/SMS.
- [ ] **1.6 Complete the copy policy.** Cover sender, signers, approvers, and CC recipients according
  to explicit settings. Generate absolute links and a usable access-code journey. Distinguish a
  recipient finishing their step from the entire request finishing.
- [ ] **1.7 Harden audit claims.** Capture trusted server timestamps and network context, version
  consent text, and verify evidence against committed revision hashes. Define the trust anchor for
  tamper evidence. Document that a provider accepting a message is not proof the recipient received it.

### Acceptance Checks

- Concurrent signatures retain every accepted recipient's marks and an ordered revision/audit chain.
- A repeated submission produces one committed signature step and no duplicate outbox record.
- Injected failures at every publication boundary leave either the prior valid state or a recoverable
  new state. A successful response is never returned before durable commitment.
- Delivery retries survive restart; permanent failures can be inspected and retried. External
  delivery is treated as at-least-once unless the provider supports an idempotency guarantee.
- Owner isolation, recipient routing, lockout, expiry, cancellation, and completed-copy access pass.

Likely touchpoints: `ESignatureWorkflowService`, `ESignaturePdfService`, notification/webhook services,
workflow models, controller contracts, and their integration tests.

## 2. One Coherent Document Workspace

Outcome: users upload once and continue editing, processing, reviewing, and exporting without losing
their place or having to understand the application's internal tool boundaries.

### Implementation Sequence

- [ ] **2.1 Inventory cross-tool state.** Map document identity/revision, active page, selection,
  zoom, scroll position, tool parameters, drafts, and operation status to their current owners.
- [ ] **2.2 Define a workspace session contract.** Extend FileContext and existing persistence with
  per-document view state and durable draft references. Keep large PDF bytes out of React state.
- [ ] **2.3 Extend existing history.** Add a bounded multi-step command history with undo/redo for
  editable operations, grouping continuous drags into one action. Represent irreversible operations
  explicitly. Recover a prior signed revision as a separate document rather than rewriting evidence.
- [ ] **2.4 Add revision review.** Offer revision history, side-by-side/synchronised comparison,
  restore-as-copy, and clear identification of the document being exported.
- [ ] **2.5 Add session recovery.** Autosave drafts with debouncing, version their storage schema,
  restore after refresh/crash, and handle quota/corrupt-record errors visibly. Apply a privacy and
  retention policy to persisted drafts; do not persist recipient access codes or signing tokens.
- [ ] **2.6 Unify operation feedback.** Reuse existing job/cancellation services for shared running,
  completed, failed, and cancelled states. Allow tool switching during supported background operations.

### Acceptance Checks

- A merge -> annotate -> sign -> export journey preserves the intended document and view state.
- Refresh restores a saved draft; unsaved state is accurately identified when persistence fails.
- Undo/redo preserves input documents and does not leak blob URLs, workers, or PDF.js handles.
- Previewing a result does not silently replace the active document or pollute its history.

Likely touchpoints: FileContext, file actions/lifecycle/storage, useToolOperation, workspace navigation,
and existing viewer state. Extend current ownership instead of introducing a second file store.

## 3. Precise PDF Authoring

Outcome: users can place and inspect fields confidently at every zoom level, using mouse, touch,
or keyboard, and the exported PDF matches their preview.

### Implementation Sequence

- [ ] **3.1 Stabilize geometry.** Define shared transforms between page coordinates and viewport
  coordinates, including rotation, crop boxes, mixed page sizes, zoom, and display scaling.
- [ ] **3.2 Introduce selection and a field inspector.** Add single/multi-selection, field labels,
  recipient assignment, required/read-only settings, validation, supported field options, and precise
  numeric position/size controls. Keep visual, API, and exported field behavior aligned.
- [ ] **3.3 Improve placement.** Add snapping to page edges/other fields, alignment guides,
  distribution, duplicate/copy/paste, multi-field movement, and clear collision/out-of-page feedback.
- [ ] **3.4 Complete keyboard and touch operations.** Add arrow movement, larger modified steps,
  keyboard resize, selection navigation, escape/cancel, and undoable deletion. Preserve page scrolling
  and pinch zoom when a field is not being manipulated.
- [ ] **3.5 Add reusable appearances.** Provide supported typed/drawn/image appearances with
  preview, manageable saved variants, sensible image limits, and tenant/user scoping where relevant.
- [ ] **3.6 Add sender preflight.** Identify missing assignees, required fields, unsupported values,
  invisible placements, and existing signed-document restrictions. Provide recipient preview before send.

### Acceptance Checks

- Screen placements map to exported PDF coordinates within a proposed one-PDF-point tolerance,
  including rotated/cropped fixtures. Validate appearance as well as numeric geometry.
- Selection, alignment, duplication, and undo work across zoom levels and mixed page dimensions.
- A keyboard-only user can create, configure, place, resize, and remove a field.
- The UI cannot advertise a field type that the recipient renderer or PDF writer cannot handle.

Likely touchpoints: SigningFieldAuthoringContext, SigningFieldPageOverlay, shared signing types,
PDF viewer integration, PDF writer, and field-validation code.

## 4. Effortless Recipient Experience

Outcome: recipients understand what they are signing, complete only their assigned fields, and can
recover from connection problems on desktop or mobile.

### Implementation Sequence

- [ ] **4.1 Use explicit journey states.** Model loading, authentication, review, filling, submitting,
  recipient-complete, request-complete, declined, expired, revoked, locked, and unavailable states.
- [ ] **4.2 Integrate document rendering.** Reuse the established PDF viewer with recipient-aware
  overlays and appropriate range loading. Confirm mobile PDF preview behavior instead of relying
  solely on the browser's native PDF iframe.
- [ ] **4.3 Guide field completion.** Add progress, next-required-field navigation, field/page
  highlighting, type-specific controls, clear inline errors, and consistent server-derived date values.
- [ ] **4.4 Complete signing choices.** Expose supported typed/drawn/image modes and let recipients
  review the appearance before acceptance. Keep consent separate from prefilled field values.
- [ ] **4.5 Handle interrupted sessions.** Preserve permitted draft values, show unsent/submitting
  status, retry through idempotent APIs, and distinguish an accepted signature from a failed follow-up
  download. Scope draft storage and delete it on completion/expiry according to policy.
- [ ] **4.6 Design completion and exceptions.** Show what was recorded, whether others still need
  to act, and when the final document is available. Give decline, expiry, and lockout their own outcomes.
- [ ] **4.7 Verify mobile accessibility.** Test focus movement, screen-reader announcements, virtual
  keyboard obstruction, pinch zoom, touch targets, portrait/landscape, and long translated labels.

### Acceptance Checks

- Complete the same request on desktop Chromium/Firefox/WebKit and representative mobile browsers.
- Recipients can review the document and reach every required field without horizontal form scrolling.
- A dropped connection and a repeated tap cannot create a second signature; resuming shows server state.
- Successful signing followed by a failed PDF refresh still shows signing success accurately.

Likely touchpoints: RecipientSigningPage, recipientSigningService, public token endpoints, existing
viewer components, CSS, and Playwright recipient journeys.

## 5. Predictable Performance and Scale

Outcome: performance stays responsive as PDFs, request histories, and concurrent users grow, with
clear resource limits and recoverable failures.

### Implementation Sequence

- [ ] **5.1 Instrument before optimizing.** Measure upload, first-page render, field interaction,
  signing, download, token lookup, dashboard queries, queue delay, heap, browser memory, and disk usage.
  Redact document content, tokens, access codes, and private recipient data from telemetry.
- [ ] **5.2 Index workflow queries.** Replace full metadata scans with indexed token-hash lookup and
  owner/status/update-time queries using persistence suited to the supported deployment. Migrate
  existing records, support rebuild/recovery, and avoid maintaining two conflicting sources of truth.
- [ ] **5.3 Paginate dashboards and events.** Add stable cursor-based APIs, filtering, bounded
  responses, incremental UI loading, and appropriate rendering virtualization.
- [ ] **5.4 Bound document processing resources.** Reuse streaming/range access, disk-backed parsing,
  page virtualization, worker cleanup, and bounded caches. Audit anchor discovery and recipient
  downloads for whole-file buffering. Publish operation-specific limits instead of assuming every
  operation can process a 100 GiB document because upload accepts that size.
- [ ] **5.5 Strengthen job execution.** Define concurrency and queue limits, fair scheduling,
  cancellation checkpoints, admission checks for temporary disk space, and retry behavior for jobs
  that are safe to replay. Long operations expose progress without artificial completion timers.
- [ ] **5.6 Benchmark and tune hotspots.** Profile repeated parsing, hashing, copies, rendering,
  and scans. Optimize the largest measured contributors and compare before/after results.

### Proposed Targets to Confirm After Baseline

- Token lookup and dashboard responses: p95 under 300 ms at 10,000 synthetic workflows on a documented
  reference server, excluding network latency and unrelated background jobs.
- Ordinary field interactions: p95 under 100 ms on the reference desktop; record mobile separately.
- First-page rendering: propose a two-second target for the agreed small-document corpus after the
  required bytes are available; measure transfer time separately.
- Repeated tool switching and cancellation show bounded resource use, without steadily accumulating
  PDF handles, URLs, workers, or temporary files.
- Large-file stress tests establish supported limits with no process crash and actionable errors on
  quota, queue, memory, and disk exhaustion. Expand sizes only as hardware permits.

Dependencies: reconcile persistence with workstream 1 before choosing indexes or job coordination.

## 6. Useful, Reviewable Intelligence

Outcome: suggestions reduce repetitive work while users retain control of document changes and sending.

### Implementation Sequence

- [ ] **6.1 Start with deterministic assistance.** Detect existing PDF fields and common labels,
  suggest recipient/field mappings, and identify incomplete assignments using the current schema.
- [ ] **6.2 Add optional document understanding.** Reuse the engine and OCR capabilities for scanned
  documents. Preserve coordinates and source-page evidence for each suggestion.
- [ ] **6.3 Build a review interface.** Preview proposed changes, allow per-item accept/edit/reject,
  mark uncertain suggestions, and apply accepted changes through the same undoable authoring actions.
- [ ] **6.4 Add contextual next actions.** Suggest actions such as completing a missing field or
  reviewing an unsigned revision based on current state and available tools. Avoid generic prompts.
- [ ] **6.5 Define data and execution boundaries.** Make remote processing optional and explicit,
  respect deployment policies, treat document text as untrusted input, and prevent extracted content
  from authorizing signing, sending, or other external actions.
- [ ] **6.6 Evaluate on a held-out corpus.** Measure field detection precision/recall, placement
  accuracy, correction time, inference latency, and processing cost against manual authoring.

### Acceptance Checks

- Suggestions never sign or send automatically; users can inspect their source and undo accepted edits.
- Proposed initial quality target: at least 90% precision for high-confidence field suggestions on
  the agreed corpus, with recall and unsupported cases published alongside it.
- Representative users prepare the same document faster than the measured manual baseline, without
  increased missed-required-field errors. Local-only deployments retain a usable workflow.

Dependencies: stable authoring schema, geometry, history, and validation from workstreams 2 and 3.

## 7. Consistent Visual Identity and Interaction

Outcome: the product feels coherent, readable, and purpose-built across tools, rather than a collection
of independently styled screens.

### Implementation Sequence

- [ ] **7.1 Audit real screens.** Capture the workspace, file list, viewer, tool settings, dashboard,
  signing journey, dialogs, loading states, errors, and empty states at desktop/mobile widths.
- [ ] **7.2 Consolidate existing design tokens.** Standardize type scale, spacing, surfaces, borders,
  focus rings, density, semantic colors, and motion through the existing Mantine/theme system.
- [ ] **7.3 Standardize common controls.** Reuse the installed icon system, toolbar dimensions,
  button hierarchy, menus, tables, field inspectors, confirmations, and feedback patterns.
- [ ] **7.4 Improve layout and hierarchy.** Keep documents prominent, settings compact, navigation
  predictable, and dashboards easy to scan. Establish responsive behavior for side panels and toolbars.
- [ ] **7.5 Complete feedback states.** Provide accurate progress, stable loading layouts, useful
  empty states, specific recoverable errors, and restrained success feedback. Respect reduced motion.
- [ ] **7.6 Validate with users.** Observe first-time and repeat users completing the same three
  baseline journeys. Record completion, time, misclicks, uncertainty, and error recovery; revise the
  highest-friction interactions before rolling the patterns out to remaining tools.

### Acceptance Checks

- No clipped controls, unreadable labels, or unintended overlaps at supported widths and 200% zoom.
- Focus, selected, disabled, error, loading, and success states are distinct and consistent.
- Semantics do not depend on color alone; long source labels and representative RTL layouts remain usable.
- Users can locate the current document, next action, and operation result without assistance.

Dependencies: audit/design can begin early; broad rollout follows stable workspace and authoring flows.

## 8. Demonstrated Production Readiness

Outcome: releases have reproducible evidence of behavior, performance, compatibility, recovery, and
operability, with external dependencies clearly identified.

### Implementation Sequence

- [x] **8.1 Repair time-sensitive test fixtures.** Generate appropriate test certificates relative to
  a controlled test time or inject a clock where needed. Preserve explicit expired/not-yet-valid cases;
  never bypass production certificate validity checks to make tests pass.
- [ ] **8.2 Add browser workflow gates.** Cover sender draft -> delivery -> recipient authentication
  -> field completion -> routing -> final PDF/evidence, plus cancellation, expiry, and failures.
  Use deterministic local provider doubles for CI and separate controlled real-provider tests.
- [ ] **8.3 Add accessibility verification.** Combine automated checks with keyboard and screen-reader
  exercises. Assess applicable WCAG 2.2 AA criteria and record manual results; do not claim conformance
  from an automated scan alone.
- [ ] **8.4 Establish PDF interoperability evidence.** Exercise enabled PAdES profiles, RSA/ECDSA,
  existing fields, multiple signatures, timestamps, revocation, and trust-path failures with independent
  validators. Record validator versions and trust settings; use synthetic, nonconfidential documents.
- [ ] **8.5 Validate security boundaries.** Cover cross-owner access, token lifetime/rotation,
  authentication throttling, uploads, SSRF, trusted proxy handling, secrets in logs, evidence integrity,
  and destructive-operation confirmation. Apply findings to the reconciled implementation.
- [ ] **8.6 Run scale and recovery exercises.** Combine parallel recipients, worker restarts,
  interrupted writes, provider outages, disk/quota exhaustion, backup restore, and migration upgrades.
  Validate observable document and workflow outcomes, not merely HTTP status codes.
- [ ] **8.7 Wire evidence into CI and releases.** Run fast contract/unit checks for relevant changes,
  browser journeys in integration CI, and scheduled performance/provider/interoperability checks where
  supported. Store reports/traces as artifacts and gate promotion on required results.
- [ ] **8.8 Complete operator documentation.** Document setup, provider configuration, supported
  topology, capacity, retention, backup/restore, migrations, rollback constraints, alerts, and incident
  procedures. Identify who owns each external acceptance gate.

### Acceptance Checks

- Every applicable row in `docs/signing-release-gates.md` links to a result for the candidate revision.
- Required checks pass without hidden skipped scenarios; unavailable external gates remain pending.
- A clean deployment can complete the main journeys, recover from the tested failure scenarios,
  and restore a backup following the documented procedure.

## Delivery Order and Dependencies

1. **Baseline:** locate completed signing work; reproduce current failures and capture user journeys.
2. **Reliability foundation:** workstream 1 plus test-fixture repair, instrumentation, and persistence
   decisions from workstreams 5 and 8.
3. **Workspace foundation:** workstream 2, query indexing/pagination, and the visual audit/token work.
4. **Precision and recipient experience:** workstreams 3 and 4; apply shared visual patterns as these
   experiences mature. Add browser acceptance tests with each increment.
5. **Measured optimization:** complete workstream 5 using before/after benchmarks and operational limits.
6. **Intelligent assistance:** workstream 6 after authoring, history, and validation contracts stabilize.
7. **Release qualification:** finish workstreams 7 and 8, publish evidence, and resolve all required gates.

Accessibility, security, translation coverage, and performance checks accompany each phase rather than
being postponed until release. Visual design work can proceed alongside backend reliability work.
Estimate effort after Phase 0; unlocated signing changes and deployment topology materially affect scope.

## Delivery Rules

- Each logical increment includes implementation, focused verification, relevant documentation, and a
  clear commit. Follow the user's standing commit/push preference when implementation resumes.
- Use existing FileContext, tool hooks, layer resolution, UI libraries, and job APIs. Use `@app/*`
  imports and source translation changes in `en-GB` only.
- Do not commit local diagnostic folders or failed-test marker files.
- Record schema/API changes and migration/rollback behavior before rollout. Version incompatible
  contracts so API, automation, MCP, and n8n consumers remain compatible.
- Measure proposed performance/quality targets on the documented baseline before treating them as
  guarantees. Do not declare a workstream complete from implementation presence alone.

## Delivery Ledger

2026-09-13 increment: repaired PAdES fixture (three tests passing), implemented distinct recipient
outcomes and required-field navigation, added public recipient routing to proprietary/SaaS layers,
and preserved credit fallback values explicitly. Five recipient component tests, translation coverage,
all frontend typechecks, ESLint, and two Chromium browser checks (1440px/390px) pass. Browser checks
use mocked APIs and do not establish live email/KMS behavior. Remaining workstream tasks stay pending.

| Workstream | Current status | Implementation revision | Acceptance evidence | Next action |
| --- | --- | --- | --- | --- |
| Baseline | Pending reconciliation | Not recorded | Last remote check: `032af038d` | Locate owner's completed signing revision |
| 1. Signing reliability | Verification pending | Not recorded | Earlier local audit only | Reconcile and reproduce concurrency/delivery behavior |
| 2. Unified workspace | Planned; foundations exist | Not recorded | No new acceptance run | Map existing state and history ownership |
| 3. Precise authoring | Planned; drag/resize exists | Not recorded | No new acceptance run | Establish geometry fixtures and selection contract |
| 4. Recipient experience | Outcomes and email step-up implemented | `b90965824` plus current increment | 6 component tests; 4 mocked-API browser scenarios | Real-device and live-provider acceptance |
| 5. Performance | Measurement pending | Not recorded | No reference benchmark | Define corpus, hardware, and instrumentation |
| 6. Intelligence | Planned | Not recorded | No evaluation run | Define deterministic suggestion baseline |
| 7. Visual consistency | Audit pending | Not recorded | Source review only | Capture representative screens and journeys |
| 8. Production readiness | Incomplete evidence; dated fixture repaired | `831801482` plus current increment | 3 PAdES tests; 23 OTP/workflow tests | Complete concurrency, provider, recovery, and interoperability gates |

### eKYC Alignment Increment (2026-09-13)

- Completed: optional sender-selected email OTP, explicit consent before code delivery, review/code/
  sign recipient flow, expiry and resend controls, and persisted one-use challenge verification.
- Challenge binding includes the server-computed document hash, revision, field schema, recipient,
  consent, signature input, and submitted field values. Challenge storage follows request retention.
- Verified: 6 challenge tests and 17 workflow tests pass; four mocked-API browser scenarios cover
  email-link/email-code signing at 1440px and 390px. All frontend layer typechecks and changed-file
  ESLint pass. These checks do not establish real-device, live-provider, or production acceptance.
- Still required: serialize complete workflow/PDF mutations, seal recipient revisions through the
  configured KMS/certificate adapter, verify the resulting PDF cryptographically, add durable delivery
  and authentication audit events, and exercise live-provider failure/recovery scenarios.
- Email OTP proves access to the recipient mailbox, not verified legal identity or QES status. The
  eKYC reference flow is adapted to Stirling's providers; no eKYC credentials or deployment data were
  copied. The eight workstreams above remain incomplete until their acceptance checks are satisfied.
