package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.api.esign.ESignatureActionResponse;
import stirling.software.SPDF.model.api.esign.ESignatureAuditEventView;
import stirling.software.SPDF.model.api.esign.ESignatureCancelRequest;
import stirling.software.SPDF.model.api.esign.ESignatureCreateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureNotification;
import stirling.software.SPDF.model.api.esign.ESignatureRecipientRequest;
import stirling.software.SPDF.model.api.esign.ESignatureRequestView;
import stirling.software.SPDF.model.api.esign.ESignatureSendRequest;
import stirling.software.SPDF.model.api.esign.ESignatureSignRequest;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.AuditEventType;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WorkflowStatus;
import stirling.software.SPDF.model.signing.SigningField;
import stirling.software.SPDF.model.signing.SigningField.NormalizedBounds;
import stirling.software.SPDF.model.signing.SigningRecipient;
import stirling.software.SPDF.model.signing.SigningRecipient.Status;
import stirling.software.SPDF.service.ESignatureWorkflowService.ActorContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap item 1.1: reproduce concurrent and repeated recipient actions and assert on the PDF
 * revision count, recipient states, and audit ordering together.
 */
class ESignatureWorkflowConcurrencyTest {

    @TempDir Path tempDir;

    private ActorContext actor;

    @BeforeEach
    void setUp() {
        actor = new ActorContext("Requester", "requester@example.com", "127.0.0.1", "JUnit");
    }

    @Test
    void twoRecipientsSigningConcurrentlyKeepBothRevisionsInOrder() throws Exception {
        ESignatureWorkflowService service = service(new DelayingPdfService(400));
        ESignatureRequestView created =
                service.createRequest(pdfFile(), parallelCreateRequest(), actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String firstToken = tokenFor(sent, "first-recipient");
        String secondToken = tokenFor(sent, "second-recipient");

        List<Throwable> failures =
                runConcurrently(
                        () -> service.sign(firstToken, signRequest("First Signer"), actor),
                        () -> service.sign(secondToken, signRequest("Second Signer"), actor));

        assertEquals(List.of(), failures, "both signatures must be accepted");
        ESignatureRequestView request = service.getRequest(created.getId());
        assertEquals(WorkflowStatus.COMPLETED, request.getStatus());
        assertEquals(2, request.getDocumentRevision());
        assertTrue(
                request.getRecipients().stream()
                        .allMatch(recipient -> recipient.getStatus() == Status.SIGNED));

        Path document = tempDir.resolve(created.getId()).resolve("document.pdf");
        assertEquals(3, countPdfRevisions(document), "original plus one revision per signer");
        String text = extractText(document);
        assertTrue(text.contains("First Signer"), text);
        assertTrue(text.contains("Second Signer"), text);

        List<ESignatureAuditEventView> audit = service.getAuditTrail(created.getId());
        List<AuditEventType> types = audit.stream().map(ESignatureAuditEventView::getType).toList();
        assertEquals(2, count(types, AuditEventType.DOCUMENT_UPDATED));
        assertEquals(2, count(types, AuditEventType.RECIPIENT_SIGNED));
        assertEquals(1, count(types, AuditEventType.REQUEST_COMPLETED));
        assertEquals(AuditEventType.REQUEST_COMPLETED, types.getLast());
        List<ESignatureAuditEventView> updates =
                audit.stream()
                        .filter(event -> event.getType() == AuditEventType.DOCUMENT_UPDATED)
                        .toList();
        assertEquals("1", updates.get(0).getDetails().get("revision"));
        assertEquals("2", updates.get(1).getDetails().get("revision"));
        assertEquals(
                updates.get(0).getDetails().get("afterSha256"),
                updates.get(1).getDetails().get("beforeSha256"),
                "the second revision must build on the first");
        assertEquals(sha256(document), updates.get(1).getDetails().get("afterSha256"));
        assertTrue(service.getEvidence(created.getId(), actor).isAuditIntegrityValid());
    }

    @Test
    void sameRecipientSubmittingTwiceCommitsExactlyOneSignatureStep() throws Exception {
        ESignatureWorkflowService service = service(new DelayingPdfService(400));
        ESignatureRequestView created =
                service.createRequest(pdfFile(), singleSignerRequest(), actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String token = tokenFor(sent, "recipient-1");

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> failures =
                runConcurrently(
                        () ->
                                countOutcome(
                                        accepted,
                                        conflicts,
                                        () ->
                                                service.sign(
                                                        token, signRequest("Only Signer"), actor)),
                        () ->
                                countOutcome(
                                        accepted,
                                        conflicts,
                                        () ->
                                                service.sign(
                                                        token, signRequest("Only Signer"), actor)));

        assertEquals(List.of(), failures);
        assertEquals(1, accepted.get(), "exactly one submission is committed");
        assertEquals(1, conflicts.get(), "the duplicate is rejected with 409");
        ESignatureRequestView request = service.getRequest(created.getId());
        assertEquals(WorkflowStatus.COMPLETED, request.getStatus());
        assertEquals(1, request.getDocumentRevision());
        Path document = tempDir.resolve(created.getId()).resolve("document.pdf");
        assertEquals(2, countPdfRevisions(document), "one incremental revision only");
        List<AuditEventType> types =
                service.getAuditTrail(created.getId()).stream()
                        .map(ESignatureAuditEventView::getType)
                        .toList();
        assertEquals(1, count(types, AuditEventType.DOCUMENT_UPDATED));
        assertEquals(1, count(types, AuditEventType.RECIPIENT_SIGNED));
        assertEquals(1, count(types, AuditEventType.REQUEST_COMPLETED));

        // A later sequential retry is still rejected and changes nothing.
        ResponseStatusException retry =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.sign(token, signRequest("Only Signer"), actor));
        assertEquals(HttpStatus.CONFLICT, retry.getStatusCode());
        assertEquals(2, countPdfRevisions(document));
        assertEquals(1, service.getRequest(created.getId()).getDocumentRevision());
    }

    @Test
    void cancellationDuringSignatureIsNeverLost() throws Exception {
        PausingPdfService pdfService = new PausingPdfService();
        ESignatureWorkflowService service = service(pdfService);
        ESignatureRequestView created =
                service.createRequest(pdfFile(), parallelCreateRequest(), actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String firstToken = tokenFor(sent, "first-recipient");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ESignatureActionResponse> signing =
                    executor.submit(() -> service.sign(firstToken, signRequest("First"), actor));
            assertTrue(pdfService.entered.await(5, TimeUnit.SECONDS), "signature started");
            Future<ESignatureRequestView> cancelling =
                    executor.submit(
                            () ->
                                    service.cancel(
                                            created.getId(), new ESignatureCancelRequest(), actor));
            // Give the cancellation a chance to run while the signature is mid-flight.
            Thread.sleep(300);
            pdfService.release.countDown();
            ESignatureActionResponse signed = signing.get(10, TimeUnit.SECONDS);
            ESignatureRequestView cancelled = cancelling.get(10, TimeUnit.SECONDS);

            assertEquals(Status.SIGNED, signed.getRequest().getRecipients().get(0).getStatus());
            assertEquals(WorkflowStatus.CANCELLED, cancelled.getStatus());
        } finally {
            executor.shutdownNow();
        }

        ESignatureRequestView request = service.getRequest(created.getId());
        assertEquals(WorkflowStatus.CANCELLED, request.getStatus(), "a successful cancel persists");
        assertEquals(1, request.getDocumentRevision(), "the accepted signature persists");
        assertEquals(Status.SIGNED, request.getRecipients().get(0).getStatus());
        List<ESignatureAuditEventView> audit = service.getAuditTrail(created.getId());
        List<AuditEventType> types = audit.stream().map(ESignatureAuditEventView::getType).toList();
        int signedIndex = types.indexOf(AuditEventType.RECIPIENT_SIGNED);
        int cancelledIndex = types.indexOf(AuditEventType.REQUEST_CANCELLED);
        assertTrue(signedIndex >= 0, "signature audit event recorded: " + types);
        assertTrue(cancelledIndex >= 0, "cancellation audit event recorded: " + types);
        assertTrue(signedIndex < cancelledIndex, "signature precedes cancellation: " + types);
        Path document = tempDir.resolve(created.getId()).resolve("document.pdf");
        assertEquals(2, countPdfRevisions(document));
        assertEquals(
                sha256(document),
                audit.stream()
                        .filter(event -> event.getType() == AuditEventType.DOCUMENT_UPDATED)
                        .findFirst()
                        .orElseThrow()
                        .getDetails()
                        .get("afterSha256"));
        assertTrue(service.getEvidence(created.getId(), actor).isAuditIntegrityValid());

        // A signature submitted after cancellation is refused without touching the document.
        String secondToken = tokenFor(sent, "second-recipient");
        ResponseStatusException afterCancel =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.sign(secondToken, signRequest("Second"), actor));
        assertEquals(HttpStatus.GONE, afterCancel.getStatusCode());
        assertEquals(2, countPdfRevisions(document));
        assertEquals(1, service.getRequest(created.getId()).getDocumentRevision());
    }

    @Test
    void signatureAfterExpiryIsRejectedAndDocumentUntouched() throws Exception {
        ESignatureWorkflowService service = service(new ESignaturePdfService());
        ESignatureCreateRequest create = singleSignerRequest();
        create.setExpiresAt(Instant.now().plusMillis(1500));
        ESignatureRequestView created = service.createRequest(pdfFile(), create, actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String token = tokenFor(sent, "recipient-1");
        Path document = tempDir.resolve(created.getId()).resolve("document.pdf");
        String originalHash = sha256(document);
        Thread.sleep(1700);

        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.sign(token, signRequest("Late Signer"), actor));

        assertEquals(HttpStatus.GONE, error.getStatusCode());
        ESignatureRequestView request = service.getRequest(created.getId());
        assertEquals(WorkflowStatus.EXPIRED, request.getStatus());
        assertEquals(Status.EXPIRED, request.getRecipients().get(0).getStatus());
        assertEquals(0, request.getDocumentRevision());
        assertEquals(originalHash, sha256(document));
        assertEquals(1, countPdfRevisions(document));
        List<AuditEventType> types =
                service.getAuditTrail(created.getId()).stream()
                        .map(ESignatureAuditEventView::getType)
                        .toList();
        assertEquals(AuditEventType.REQUEST_EXPIRED, types.getLast());
        assertEquals(0, count(types, AuditEventType.RECIPIENT_SIGNED));
        assertEquals(0, count(types, AuditEventType.DOCUMENT_UPDATED));
    }

    // ---- helpers -------------------------------------------------------------------------

    private ESignatureWorkflowService service(ESignaturePdfService pdfService) {
        return new ESignatureWorkflowService(JsonMapper.builder().build(), tempDir, pdfService);
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    static List<Throwable> runConcurrently(Action... actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(actions.length);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Action action : actions) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    action.run();
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return failures;
    }

    static void countOutcome(AtomicInteger accepted, AtomicInteger conflicts, Action action)
            throws Exception {
        try {
            action.run();
            accepted.incrementAndGet();
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                conflicts.incrementAndGet();
            } else {
                throw e;
            }
        }
    }

    static String tokenFor(ESignatureActionResponse response, String recipientId) {
        return response.getNotifications().stream()
                .filter(notification -> recipientId.equals(notification.getRecipientId()))
                .map(ESignatureNotification::getToken)
                .findFirst()
                .orElseThrow();
    }

    static int count(List<AuditEventType> types, AuditEventType type) {
        return (int) types.stream().filter(type::equals).count();
    }

    static int countPdfRevisions(Path document) throws IOException {
        byte[] bytes = Files.readAllBytes(document);
        byte[] marker = "%%EOF".getBytes();
        int count = 0;
        outer:
        for (int i = 0; i <= bytes.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (bytes[i + j] != marker[j]) continue outer;
            }
            count++;
        }
        return count;
    }

    static String extractText(Path document) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(document.toFile())) {
            return new PDFTextStripper().getText(pdf);
        }
    }

    static String sha256(Path path) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    static ESignatureCreateRequest parallelCreateRequest() {
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        request.setTitle("Parallel NDA");
        request.setSigningOrder(false);
        request.setRemindersEnabled(false);
        ESignatureRecipientRequest first = recipient("First", "first@example.com", 1);
        first.setId("first-recipient");
        ESignatureRecipientRequest second = recipient("Second", "second@example.com", 1);
        second.setId("second-recipient");
        request.setRecipients(List.of(first, second));
        request.setFields(
                List.of(
                        signatureField("first-recipient", 0.1, 0.7, 0.35, 0.08),
                        signatureField("second-recipient", 0.1, 0.82, 0.35, 0.08)));
        return request;
    }

    static ESignatureCreateRequest singleSignerRequest() {
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        request.setTitle("Single signer");
        request.setRemindersEnabled(false);
        ESignatureRecipientRequest recipient = recipient("Signer", "signer@example.com", 1);
        recipient.setId("recipient-1");
        request.setRecipients(List.of(recipient));
        request.setFields(List.of(signatureField("recipient-1", 0.5, 0.75, 0.35, 0.1)));
        return request;
    }

    static ESignatureRecipientRequest recipient(String name, String email, int signingOrder) {
        ESignatureRecipientRequest recipient = new ESignatureRecipientRequest();
        recipient.setName(name);
        recipient.setEmail(email);
        recipient.setSigningOrder(signingOrder);
        return recipient;
    }

    static ESignatureSignRequest signRequest(String name) {
        ESignatureSignRequest request = new ESignatureSignRequest();
        request.setSignerName(name);
        request.setConsentAccepted(true);
        request.setConsentText("I agree to sign electronically.");
        request.setSignatureType("typed");
        return request;
    }

    static SigningField signatureField(
            String recipientId, double x, double y, double width, double height) {
        NormalizedBounds bounds = new NormalizedBounds();
        bounds.setX(x);
        bounds.setY(y);
        bounds.setWidth(width);
        bounds.setHeight(height);
        SigningField field = new SigningField();
        field.setId("field-" + recipientId);
        field.setRecipientId(recipientId);
        field.setName("signature-" + recipientId);
        field.setLabel("Signature");
        field.setType(SigningField.Type.SIGNATURE);
        field.setPageIndex(0);
        field.setBounds(bounds);
        return field;
    }

    static MockMultipartFile pdfFile() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(output);
        }
        return new MockMultipartFile(
                "file", "contract.pdf", "application/pdf", output.toByteArray());
    }

    /** Slows every PDF revision so overlapping submissions genuinely overlap. */
    static class DelayingPdfService extends ESignaturePdfService {
        private final long delayMillis;

        DelayingPdfService(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public AppliedRevision applyRecipientFields(
                Path documentPath,
                List<SigningField> fields,
                SigningRecipient recipient,
                ESignatureSignRequest request,
                Instant signedAt)
                throws IOException {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            return super.applyRecipientFields(documentPath, fields, recipient, request, signedAt);
        }
    }

    /** Pauses the first PDF revision until the test releases it. */
    static class PausingPdfService extends ESignaturePdfService {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AppliedRevision applyRecipientFields(
                Path documentPath,
                List<SigningField> fields,
                SigningRecipient recipient,
                ESignatureSignRequest request,
                Instant signedAt)
                throws IOException {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS), "test released the revision");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            return super.applyRecipientFields(documentPath, fields, recipient, request, signedAt);
        }
    }
}
