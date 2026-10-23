package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static stirling.software.SPDF.service.ESignatureWorkflowConcurrencyTest.count;
import static stirling.software.SPDF.service.ESignatureWorkflowConcurrencyTest.countPdfRevisions;
import static stirling.software.SPDF.service.ESignatureWorkflowConcurrencyTest.parallelCreateRequest;
import static stirling.software.SPDF.service.ESignatureWorkflowConcurrencyTest.pdfFile;
import static stirling.software.SPDF.service.ESignatureWorkflowConcurrencyTest.runConcurrently;
import static stirling.software.SPDF.service.ESignatureWorkflowConcurrencyTest.sha256;
import static stirling.software.SPDF.service.ESignatureWorkflowConcurrencyTest.signRequest;
import static stirling.software.SPDF.service.ESignatureWorkflowConcurrencyTest.singleSignerRequest;
import static stirling.software.SPDF.service.ESignatureWorkflowConcurrencyTest.tokenFor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.exception.CodedResponseStatusException;
import stirling.software.SPDF.model.api.esign.ESignatureActionResponse;
import stirling.software.SPDF.model.api.esign.ESignatureAuditEventView;
import stirling.software.SPDF.model.api.esign.ESignatureCancelRequest;
import stirling.software.SPDF.model.api.esign.ESignatureCreateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureDeclineRequest;
import stirling.software.SPDF.model.api.esign.ESignatureRequestView;
import stirling.software.SPDF.model.api.esign.ESignatureSendRequest;
import stirling.software.SPDF.model.api.esign.ESignatureSignRequest;
import stirling.software.SPDF.model.esign.ESignatureErrorCode;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.AuditEventType;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WorkflowStatus;
import stirling.software.SPDF.model.signing.SigningField;
import stirling.software.SPDF.model.signing.SigningRecipient;
import stirling.software.SPDF.model.signing.SigningRecipient.Method;
import stirling.software.SPDF.model.signing.SigningRecipient.Status;
import stirling.software.SPDF.service.ESignatureWorkflowService.ActorContext;
import stirling.software.common.service.SigningNotificationProvider;
import stirling.software.common.service.SigningNotificationProvider.SigningNotificationMessage;

import tools.jackson.databind.json.JsonMapper;

/** Roadmap items 1.2 and 1.3: revision checks, idempotency keys, and per-request locking. */
class ESignatureWorkflowIdempotencyTest {

    @TempDir Path tempDir;

    private ActorContext actor;

    @BeforeEach
    void setUp() {
        actor = new ActorContext("Requester", "requester@example.com", "127.0.0.1", "JUnit");
    }

    @Test
    void identicalSignSubmissionWithSameKeyReplaysStoredResultWithoutTokens() throws Exception {
        ESignatureWorkflowService service = service(new ESignaturePdfService());
        ESignatureCreateRequest create = parallelCreateRequest();
        create.setSigningOrder(true);
        create.getRecipients().get(1).setSigningOrder(2);
        ESignatureRequestView created = service.createRequest(pdfFile(), create, actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String token = tokenFor(sent, "first-recipient");
        ESignatureSignRequest request = signRequest("First");
        request.setIdempotencyKey("sign-attempt-1");

        ESignatureActionResponse first = service.sign(token, request, actor);
        ESignatureActionResponse replay = service.sign(token, request, actor);

        assertFalse(first.isReplayed());
        assertNotNull(first.getNotifications().getFirst().getToken(), "next recipient token");
        assertTrue(replay.isReplayed());
        assertEquals(first.getRequest().getId(), replay.getRequest().getId());
        assertEquals(first.getRequest().getStatus(), replay.getRequest().getStatus());
        assertEquals(
                first.getRequest().getWorkflowRevision(),
                replay.getRequest().getWorkflowRevision());
        assertEquals(1, replay.getNotifications().size());
        assertNull(replay.getNotifications().getFirst().getToken(), "tokens are never replayed");
        assertNull(replay.getNotifications().getFirst().getSigningUrl());
        String metadata =
                Files.readString(tempDir.resolve(created.getId()).resolve("metadata.json"));
        assertFalse(metadata.contains(first.getNotifications().getFirst().getToken()));
        assertTrue(metadata.contains("\"idempotencyKey\":\"sign-attempt-1\""));

        ESignatureRequestView stored = service.getRequest(created.getId());
        assertEquals(1, stored.getDocumentRevision());
        assertEquals(
                1, count(auditTypes(service, created.getId()), AuditEventType.DOCUMENT_UPDATED));
        assertEquals(
                1, count(auditTypes(service, created.getId()), AuditEventType.RECIPIENT_SIGNED));
        assertEquals(
                2, countPdfRevisions(tempDir.resolve(created.getId()).resolve("document.pdf")));
    }

    @Test
    void conflictingReuseOfAnIdempotencyKeyIsRejected() throws Exception {
        ESignatureWorkflowService service = service(new ESignaturePdfService());
        ESignatureRequestView created =
                service.createRequest(pdfFile(), singleSignerRequest(), actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String token = tokenFor(sent, "recipient-1");
        ESignatureSignRequest request = signRequest("Signer");
        request.setIdempotencyKey("shared-key");
        service.sign(token, request, actor);

        ESignatureSignRequest changed = signRequest("Different Name");
        changed.setIdempotencyKey("shared-key");
        CodedResponseStatusException error =
                assertThrows(
                        CodedResponseStatusException.class,
                        () -> service.sign(token, changed, actor));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ESignatureErrorCode.IDEMPOTENCY_KEY_REUSED, error.getCode());
        assertEquals("sign", error.getProperties().get("completedAction"));

        ESignatureCancelRequest cancel = new ESignatureCancelRequest();
        cancel.setIdempotencyKey("shared-key");
        CodedResponseStatusException otherAction =
                assertThrows(
                        CodedResponseStatusException.class,
                        () -> service.cancel(created.getId(), cancel, actor));
        assertEquals(ESignatureErrorCode.IDEMPOTENCY_KEY_REUSED, otherAction.getCode());

        ESignatureSignRequest invalidKey = signRequest("Signer");
        invalidKey.setIdempotencyKey("not valid!");
        ResponseStatusException badKey =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.sign(token, invalidKey, actor));
        assertEquals(HttpStatus.BAD_REQUEST, badKey.getStatusCode());
    }

    @Test
    void concurrentDuplicateWithSameKeyCommitsOnceAndReplaysOnce() throws Exception {
        ESignatureWorkflowService service =
                service(new ESignatureWorkflowConcurrencyTest.DelayingPdfService(300));
        ESignatureRequestView created =
                service.createRequest(pdfFile(), singleSignerRequest(), actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String token = tokenFor(sent, "recipient-1");
        ESignatureSignRequest request = signRequest("Signer");
        request.setIdempotencyKey("double-click");
        List<ESignatureActionResponse> responses = new ArrayList<>();

        List<Throwable> failures =
                runConcurrently(
                        () -> {
                            ESignatureActionResponse response = service.sign(token, request, actor);
                            synchronized (responses) {
                                responses.add(response);
                            }
                        },
                        () -> {
                            ESignatureActionResponse response = service.sign(token, request, actor);
                            synchronized (responses) {
                                responses.add(response);
                            }
                        });

        assertEquals(List.of(), failures, "both submissions succeed");
        assertEquals(2, responses.size());
        assertEquals(1, responses.stream().filter(ESignatureActionResponse::isReplayed).count());
        List<AuditEventType> types = auditTypes(service, created.getId());
        assertEquals(1, count(types, AuditEventType.DOCUMENT_UPDATED));
        assertEquals(1, count(types, AuditEventType.RECIPIENT_SIGNED));
        assertEquals(1, service.getRequest(created.getId()).getDocumentRevision());
        assertEquals(
                2, countPdfRevisions(tempDir.resolve(created.getId()).resolve("document.pdf")));
    }

    @Test
    void staleExpectedRevisionIsRejectedWithCurrentRevision() throws Exception {
        ESignatureWorkflowService service = service(new ESignaturePdfService());
        ESignatureRequestView created =
                service.createRequest(pdfFile(), singleSignerRequest(), actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String token = tokenFor(sent, "recipient-1");
        long revisionBeforeView = sent.getRequest().getWorkflowRevision();
        assertTrue(revisionBeforeView > 0);
        ESignatureActionResponse viewed = service.markViewed(token, null, actor);
        assertTrue(viewed.getRequest().getWorkflowRevision() > revisionBeforeView);

        ESignatureSignRequest stale = signRequest("Signer");
        stale.setExpectedWorkflowRevision(revisionBeforeView);
        CodedResponseStatusException error =
                assertThrows(
                        CodedResponseStatusException.class,
                        () -> service.sign(token, stale, actor));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ESignatureErrorCode.WORKFLOW_REVISION_STALE, error.getCode());
        assertEquals(revisionBeforeView, error.getProperties().get("expectedWorkflowRevision"));
        assertEquals(
                viewed.getRequest().getWorkflowRevision(),
                error.getProperties().get("currentWorkflowRevision"));
        assertEquals(0, service.getRequest(created.getId()).getDocumentRevision());

        ESignatureSignRequest fresh = signRequest("Signer");
        fresh.setExpectedWorkflowRevision(viewed.getRequest().getWorkflowRevision());
        ESignatureActionResponse signed = service.sign(token, fresh, actor);
        assertEquals(Status.SIGNED, signed.getRequest().getRecipients().getFirst().getStatus());
        assertEquals(
                viewed.getRequest().getWorkflowRevision() + 1,
                signed.getRequest().getWorkflowRevision());

        ESignatureRequestView otherCreated =
                service.createRequest(pdfFile(), singleSignerRequest(), actor);
        ESignatureActionResponse otherSent =
                service.sendRequest(otherCreated.getId(), new ESignatureSendRequest(), actor);
        ESignatureCancelRequest staleCancel = new ESignatureCancelRequest();
        staleCancel.setExpectedWorkflowRevision(otherSent.getRequest().getWorkflowRevision() - 1);
        CodedResponseStatusException cancelError =
                assertThrows(
                        CodedResponseStatusException.class,
                        () -> service.cancel(otherCreated.getId(), staleCancel, actor));
        assertEquals(ESignatureErrorCode.WORKFLOW_REVISION_STALE, cancelError.getCode());
        assertEquals(WorkflowStatus.SENT, service.getRequest(otherCreated.getId()).getStatus());
    }

    @Test
    void cancelAndDeclineHonourTransitionsAndIdempotencyKeys() throws Exception {
        ESignatureWorkflowService service = service(new ESignaturePdfService());
        ESignatureRequestView created =
                service.createRequest(pdfFile(), parallelCreateRequest(), actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);

        ESignatureCancelRequest cancel = new ESignatureCancelRequest();
        cancel.setReason("Superseded");
        cancel.setIdempotencyKey("cancel-1");
        ESignatureRequestView cancelled = service.cancel(created.getId(), cancel, actor);
        ESignatureRequestView again = service.cancel(created.getId(), cancel, actor);
        assertEquals(WorkflowStatus.CANCELLED, cancelled.getStatus());
        assertEquals(cancelled.getWorkflowRevision(), again.getWorkflowRevision());
        assertEquals(
                1, count(auditTypes(service, created.getId()), AuditEventType.REQUEST_CANCELLED));

        // Archive is the only move out of CANCELLED; cancelling a completed request is refused.
        ESignatureRequestView completedRequest =
                service.createRequest(pdfFile(), singleSignerRequest(), actor);
        ESignatureActionResponse completedSent =
                service.sendRequest(completedRequest.getId(), new ESignatureSendRequest(), actor);
        service.sign(tokenFor(completedSent, "recipient-1"), signRequest("Signer"), actor);
        CodedResponseStatusException cannotCancel =
                assertThrows(
                        CodedResponseStatusException.class,
                        () ->
                                service.cancel(
                                        completedRequest.getId(),
                                        new ESignatureCancelRequest(),
                                        actor));
        assertEquals(HttpStatus.CONFLICT, cannotCancel.getStatusCode());
        assertEquals(ESignatureErrorCode.WORKFLOW_STATE_INVALID, cannotCancel.getCode());
        assertEquals("COMPLETED", cannotCancel.getProperties().get("currentStatus"));

        // Decline replays under its key and blocks the other recipient afterwards.
        ESignatureRequestView declineRequest =
                service.createRequest(pdfFile(), parallelCreateRequest(), actor);
        ESignatureActionResponse declineSent =
                service.sendRequest(declineRequest.getId(), new ESignatureSendRequest(), actor);
        ESignatureDeclineRequest decline = new ESignatureDeclineRequest();
        decline.setReason("Not the right document");
        decline.setIdempotencyKey("decline-1");
        String firstToken = tokenFor(declineSent, "first-recipient");
        ESignatureActionResponse declined = service.decline(firstToken, decline, actor);
        ESignatureActionResponse declinedAgain = service.decline(firstToken, decline, actor);
        assertEquals(WorkflowStatus.DECLINED, declined.getRequest().getStatus());
        assertFalse(declined.isReplayed());
        assertTrue(declinedAgain.isReplayed());
        assertEquals(
                1,
                count(
                        auditTypes(service, declineRequest.getId()),
                        AuditEventType.RECIPIENT_DECLINED));
        CodedResponseStatusException blocked =
                assertThrows(
                        CodedResponseStatusException.class,
                        () ->
                                service.sign(
                                        tokenFor(declineSent, "second-recipient"),
                                        signRequest("Second"),
                                        actor));
        assertEquals(ESignatureErrorCode.WORKFLOW_STATE_INVALID, blocked.getCode());
        ESignatureDeclineRequest withoutKey = new ESignatureDeclineRequest();
        CodedResponseStatusException alreadyDeclined =
                assertThrows(
                        CodedResponseStatusException.class,
                        () -> service.decline(firstToken, withoutKey, actor));
        assertEquals(HttpStatus.CONFLICT, alreadyDeclined.getStatusCode());
    }

    @Test
    void resendingKeepsViewedRecipientsAndDoesNotRegressWorkflowStatus() throws Exception {
        ESignatureWorkflowService service = service(new ESignaturePdfService());
        ESignatureRequestView created =
                service.createRequest(pdfFile(), parallelCreateRequest(), actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        service.markViewed(tokenFor(sent, "first-recipient"), null, actor);
        service.sign(tokenFor(sent, "second-recipient"), signRequest("Second"), actor);

        ESignatureSendRequest resend = new ESignatureSendRequest();
        resend.setRotateTokens(true);
        resend.setIdempotencyKey("resend-1");
        ESignatureActionResponse resent = service.sendRequest(created.getId(), resend, actor);
        ESignatureActionResponse replayed = service.sendRequest(created.getId(), resend, actor);

        assertEquals(WorkflowStatus.IN_PROGRESS, resent.getRequest().getStatus());
        assertEquals(1, resent.getNotifications().size());
        assertEquals("first-recipient", resent.getNotifications().getFirst().getRecipientId());
        assertEquals(Status.VIEWED, resent.getRequest().getRecipients().get(0).getStatus());
        assertEquals(Status.SIGNED, resent.getRequest().getRecipients().get(1).getStatus());
        assertTrue(replayed.isReplayed());
        assertNull(replayed.getNotifications().getFirst().getToken());
        // The rotated token works and the pre-rotation one no longer resolves.
        service.getTokenContext(resent.getNotifications().getFirst().getToken());
        ResponseStatusException oldToken =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.getTokenContext(tokenFor(sent, "first-recipient")));
        assertEquals(HttpStatus.NOT_FOUND, oldToken.getStatusCode());
    }

    @Test
    void failedWriteAfterStepUpConsumptionIsRecordedOnTheAuditTrail() throws Exception {
        List<SigningNotificationMessage> messages = new ArrayList<>();
        SigningNotificationProvider email =
                new SigningNotificationProvider() {
                    @Override
                    public String channel() {
                        return "email";
                    }

                    @Override
                    public void send(SigningNotificationMessage message) {
                        messages.add(message);
                    }
                };
        FailingOncePdfService pdfService = new FailingOncePdfService();
        ESignatureWorkflowService service =
                new ESignatureWorkflowService(
                        JsonMapper.builder().build(), tempDir, pdfService, null, List.of(email));
        ESignatureCreateRequest create = singleSignerRequest();
        create.getRecipients().getFirst().setAuthenticationMethod(Method.EMAIL_OTP);
        ESignatureRequestView created = service.createRequest(pdfFile(), create, actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String token = tokenFor(sent, "recipient-1");
        Path document = tempDir.resolve(created.getId()).resolve("document.pdf");
        String originalHash = sha256(document);
        ESignatureSignRequest signing = signRequest("Signer");
        service.issueSigningOtp(token, signing);
        Matcher matcher = Pattern.compile("\\b[0-9]{6}\\b").matcher(messages.getLast().body());
        assertTrue(matcher.find());
        signing.setOtp(matcher.group());

        CodedResponseStatusException error =
                assertThrows(
                        CodedResponseStatusException.class,
                        () -> service.sign(token, signing, actor));

        assertEquals(ESignatureErrorCode.SIGNATURE_APPLY_FAILED, error.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatusCode());
        assertEquals(true, error.getProperties().get("stepUpConsumed"));
        List<ESignatureAuditEventView> audit = service.getAuditTrail(created.getId());
        ESignatureAuditEventView failure = audit.getLast();
        assertEquals(AuditEventType.RECIPIENT_SIGNATURE_FAILED, failure.getType());
        assertEquals("recipient-1", failure.getRecipientId());
        assertEquals("true", failure.getDetails().get("stepUpConsumed"));
        assertTrue(failure.getDetails().get("reason").contains("disk full"));
        ESignatureRequestView request = service.getRequest(created.getId());
        assertEquals(Status.SENT, request.getRecipients().getFirst().getStatus());
        assertEquals(0, request.getDocumentRevision());
        assertEquals(originalHash, sha256(document));
        assertTrue(service.getEvidence(created.getId(), actor).isAuditIntegrityValid());

        // The consumed code cannot be replayed even though the PDF service now works.
        ResponseStatusException burnt =
                assertThrows(
                        ResponseStatusException.class, () -> service.sign(token, signing, actor));
        assertEquals(HttpStatus.UNAUTHORIZED, burnt.getStatusCode());
        assertEquals(1, pdfService.attempts.get(), "no PDF write without a valid code");
    }

    @Test
    void holdsTheRequestFileLockWhileMutatingAndKeepsOtherRequestsConcurrent() throws Exception {
        ESignatureWorkflowConcurrencyTest.PausingPdfService pdfService =
                new ESignatureWorkflowConcurrencyTest.PausingPdfService();
        ESignatureWorkflowService service = service(pdfService);
        ESignatureRequestView paused =
                service.createRequest(pdfFile(), singleSignerRequest(), actor);
        ESignatureActionResponse pausedSent =
                service.sendRequest(paused.getId(), new ESignatureSendRequest(), actor);
        ESignatureRequestView other =
                service.createRequest(pdfFile(), singleSignerRequest(), actor);
        ESignatureActionResponse otherSent =
                service.sendRequest(other.getId(), new ESignatureSendRequest(), actor);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ESignatureActionResponse> signing =
                    executor.submit(
                            () ->
                                    service.sign(
                                            tokenFor(pausedSent, "recipient-1"),
                                            signRequest("Paused"),
                                            actor));
            assertTrue(pdfService.entered.await(5, TimeUnit.SECONDS));

            Path lockFile = tempDir.resolve(paused.getId()).resolve(".lock");
            assertTrue(Files.exists(lockFile), "lock file created under the request directory");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
                assertThrows(
                        OverlappingFileLockException.class,
                        channel::tryLock,
                        "the request file lock is held for the whole mutation");
            }

            // An unrelated request is not blocked by the paused one.
            Instant before = Instant.now();
            ESignatureActionResponse otherSigned =
                    service.sign(tokenFor(otherSent, "recipient-1"), signRequest("Other"), actor);
            assertEquals(WorkflowStatus.COMPLETED, otherSigned.getRequest().getStatus());
            assertTrue(
                    Instant.now().isBefore(before.plusSeconds(5)),
                    "independent request completed while the other was held");
            assertEquals(1, pdfService.release.getCount(), "paused request still in flight");

            pdfService.release.countDown();
            assertEquals(
                    WorkflowStatus.COMPLETED,
                    signing.get(10, TimeUnit.SECONDS).getRequest().getStatus());
        } finally {
            pdfService.release.countDown();
            executor.shutdownNow();
        }
        try (FileChannel channel =
                FileChannel.open(
                        tempDir.resolve(paused.getId()).resolve(".lock"),
                        StandardOpenOption.WRITE)) {
            assertNotNull(channel.tryLock(), "lock released after the mutation");
        }
    }

    private ESignatureWorkflowService service(ESignaturePdfService pdfService) {
        return new ESignatureWorkflowService(JsonMapper.builder().build(), tempDir, pdfService);
    }

    private static List<AuditEventType> auditTypes(
            ESignatureWorkflowService service, String requestId) throws IOException {
        return service.getAuditTrail(requestId).stream()
                .map(ESignatureAuditEventView::getType)
                .toList();
    }

    /** Fails the first PDF revision with an I/O error and then behaves normally. */
    static class FailingOncePdfService extends ESignaturePdfService {
        final AtomicInteger attempts = new AtomicInteger();

        @Override
        public AppliedRevision applyRecipientFields(
                Path documentPath,
                List<SigningField> fields,
                SigningRecipient recipient,
                ESignatureSignRequest request,
                Instant signedAt)
                throws IOException {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("disk full while writing the revision");
            }
            return super.applyRecipientFields(documentPath, fields, recipient, request, signedAt);
        }
    }
}
