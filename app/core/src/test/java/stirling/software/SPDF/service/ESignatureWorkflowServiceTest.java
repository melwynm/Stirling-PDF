package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.api.esign.ESignatureActionResponse;
import stirling.software.SPDF.model.api.esign.ESignatureCreateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureNotification;
import stirling.software.SPDF.model.api.esign.ESignatureRecipientRequest;
import stirling.software.SPDF.model.api.esign.ESignatureRequestView;
import stirling.software.SPDF.model.api.esign.ESignatureSendRequest;
import stirling.software.SPDF.model.api.esign.ESignatureSignRequest;
import stirling.software.SPDF.model.api.esign.ESignatureTokenContext;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.AuditEventType;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WebhookDeliveryStatus;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WorkflowStatus;
import stirling.software.SPDF.model.signing.SigningField;
import stirling.software.SPDF.model.signing.SigningField.NormalizedBounds;
import stirling.software.SPDF.model.signing.SigningRecipient.DeliveryChannel;
import stirling.software.SPDF.model.signing.SigningRecipient.Method;
import stirling.software.SPDF.model.signing.SigningRecipient.Status;
import stirling.software.SPDF.service.ESignatureWorkflowService.ActorContext;
import stirling.software.common.service.SigningNotificationProvider;
import stirling.software.common.service.SigningNotificationProvider.SigningNotificationMessage;
import stirling.software.common.service.SsrfProtectionService;

import tools.jackson.databind.json.JsonMapper;

class ESignatureWorkflowServiceTest {

    @TempDir Path tempDir;

    private ESignatureWorkflowService service;
    private ActorContext actor;

    @BeforeEach
    void setUp() {
        service = new ESignatureWorkflowService(JsonMapper.builder().build(), tempDir);
        actor = new ActorContext("Requester", "requester@example.com", "127.0.0.1", "JUnit");
    }

    @Test
    void orderedWorkflowActivatesNextRecipientAndCompletes() throws Exception {
        ESignatureRequestView created =
                service.createRequest(pdfFile(), orderedCreateRequest(), actor);

        assertEquals(WorkflowStatus.DRAFT, created.getStatus());
        assertEquals(2, created.getRecipients().size());
        assertEquals(Status.PENDING, created.getRecipients().get(0).getStatus());

        ESignatureSendRequest sendRequest = new ESignatureSendRequest();
        sendRequest.setPublicBaseUrl("https://stirling.example");
        ESignatureActionResponse sent = service.sendRequest(created.getId(), sendRequest, actor);

        assertEquals(WorkflowStatus.SENT, sent.getRequest().getStatus());
        assertEquals(1, sent.getNotifications().size());
        assertEquals(Status.SENT, sent.getRequest().getRecipients().get(0).getStatus());
        assertEquals(Status.PENDING, sent.getRequest().getRecipients().get(1).getStatus());
        String firstToken = sent.getNotifications().get(0).getToken();
        assertNotNull(firstToken);
        assertFalse(sent.getNotifications().get(0).getSigningUrl().isBlank());
        String storedMetadata =
                Files.readString(tempDir.resolve(created.getId()).resolve("metadata.json"));
        assertFalse(storedMetadata.contains(firstToken));
        assertTrue(storedMetadata.contains("signingTokenHash"));

        ESignatureTokenContext context = service.getTokenContext(firstToken);
        assertEquals(1, context.getRequest().getRecipients().size());
        assertEquals("first@example.com", context.getRecipient().getEmail());

        service.markViewed(firstToken, null, actor);
        ESignatureActionResponse firstSigned =
                service.sign(firstToken, signRequest("First"), actor);

        assertEquals(WorkflowStatus.IN_PROGRESS, firstSigned.getRequest().getStatus());
        assertEquals(1, firstSigned.getNotifications().size());
        assertEquals(Status.SIGNED, firstSigned.getRequest().getRecipients().get(0).getStatus());
        assertEquals(Status.SENT, firstSigned.getRequest().getRecipients().get(1).getStatus());

        String secondToken = firstSigned.getNotifications().get(0).getToken();
        ESignatureActionResponse completed =
                service.sign(secondToken, signRequest("Second"), actor);

        assertEquals(WorkflowStatus.COMPLETED, completed.getRequest().getStatus());
        assertEquals(Status.SIGNED, completed.getRequest().getRecipients().get(1).getStatus());
        assertEquals(
                AuditEventType.REQUEST_COMPLETED,
                service.getAuditTrail(created.getId()).getLast().getType());
    }

    @Test
    void persistsSharedFieldModelAndScopesSignerContext() throws Exception {
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        ESignatureRecipientRequest recipient = recipient("Signer", "signer@example.com", 1);
        recipient.setId("recipient-1");
        recipient.setAuthenticationMethod(Method.ACCESS_CODE);
        recipient.setAccessCode("shared-secret");
        request.setRequesterName("Request owner");
        request.setRequesterEmail("owner@example.com");
        request.setRemindersEnabled(false);
        request.setRecipients(List.of(recipient));
        request.setFields(List.of(signatureField(null, 0.55, 0.72, 0.35, 0.12)));

        ESignatureRequestView created = service.createRequest(pdfFile(), request, actor);

        assertEquals(1, created.getModelVersion());
        assertEquals(1, created.getFields().size());
        assertEquals("recipient-1", created.getFields().getFirst().getRecipientId());
        assertEquals(SigningField.Type.SIGNATURE, created.getFields().getFirst().getType());
        assertEquals("Request owner", created.getRequesterName());
        assertFalse(created.isRemindersEnabled());
        assertEquals(
                Method.ACCESS_CODE, created.getRecipients().getFirst().getAuthenticationMethod());
        assertTrue(created.getRecipients().getFirst().isAuthenticationConfigured());
        String metadata =
                Files.readString(tempDir.resolve(created.getId()).resolve("metadata.json"));
        assertFalse(metadata.contains("shared-secret"));

        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        String token = sent.getNotifications().getFirst().getToken();
        ESignatureTokenContext context = service.getTokenContext(token);

        assertEquals(1, context.getRequest().getFields().size());
        assertEquals("recipient-1", context.getRequest().getFields().getFirst().getRecipientId());
        ResponseStatusException authenticationError =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.getDocumentForToken(token, "wrong-code"));
        assertEquals(HttpStatus.UNAUTHORIZED, authenticationError.getStatusCode());
        assertNotNull(service.getDocumentForToken(token, "shared-secret").getPath());
    }

    @Test
    void rejectsOutOfBoundsFieldsBeforeCreatingStorage() throws Exception {
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        ESignatureRecipientRequest recipient = recipient("Signer", "signer@example.com", 1);
        recipient.setId("recipient-1");
        request.setRecipients(List.of(recipient));
        request.setFields(List.of(signatureField("recipient-1", 0.9, 0.1, 0.2, 0.1)));

        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.createRequest(pdfFile(), request, actor));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsFilesWithPdfExtensionButInvalidContent() throws Exception {
        MockMultipartFile invalidPdf =
                new MockMultipartFile(
                        "file", "renamed.pdf", "application/pdf", "not a pdf".getBytes());

        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.createRequest(invalidPdf, orderedCreateRequest(), actor));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void locksRecipientAuthenticationAfterFiveFailures() throws Exception {
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        ESignatureRecipientRequest recipient = recipient("Signer", "signer@example.com", 1);
        recipient.setId("recipient-1");
        recipient.setAuthenticationMethod(Method.ACCESS_CODE);
        recipient.setAccessCode("shared-secret");
        request.setRecipients(List.of(recipient));
        request.setFields(List.of(signatureField("recipient-1", 0.5, 0.75, 0.35, 0.1)));
        ESignatureRequestView created = service.createRequest(pdfFile(), request, actor);
        String token =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor)
                        .getNotifications()
                        .getFirst()
                        .getToken();

        for (int attempt = 1; attempt < 5; attempt++) {
            ResponseStatusException error =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> service.getDocumentForToken(token, "wrong-code"));
            assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        }
        ResponseStatusException lockError =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.getDocumentForToken(token, "wrong-code"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, lockError.getStatusCode());
        ResponseStatusException correctCodeWhileLocked =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.getDocumentForToken(token, "shared-secret"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, correctCodeWhileLocked.getStatusCode());
        assertEquals(
                AuditEventType.RECIPIENT_AUTHENTICATION_LOCKED,
                service.getAuditTrail(created.getId()).getLast().getType());
    }

    @Test
    void scopesAuthenticatedSenderOperationsToWorkflowOwner() throws Exception {
        ActorContext owner =
                new ActorContext("Owner", "owner@example.com", "127.0.0.1", "JUnit", "user-1");
        ActorContext otherUser =
                new ActorContext("Other", "other@example.com", "127.0.0.2", "JUnit", "user-2");
        ESignatureRequestView created =
                service.createRequest(pdfFile(), orderedCreateRequest(), owner);

        assertEquals(1, service.listRequests(null, owner).size());
        assertEquals(0, service.listRequests(null, otherUser).size());
        assertEquals(created.getId(), service.getRequest(created.getId(), owner).getId());
        ResponseStatusException hiddenFromOtherUser =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.getRequest(created.getId(), otherUser));
        assertEquals(HttpStatus.NOT_FOUND, hiddenFromOtherUser.getStatusCode());
        ResponseStatusException sendDenied =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.sendRequest(
                                        created.getId(), new ESignatureSendRequest(), otherUser));
        assertEquals(HttpStatus.NOT_FOUND, sendDenied.getStatusCode());
    }

    @Test
    void completionCreatesRequesterAndReadOnlyCopyNotifications() throws Exception {
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        request.setRequesterName("Owner");
        request.setRequesterEmail("owner@example.com");
        ESignatureRecipientRequest signer = recipient("Signer", "signer@example.com", 1);
        signer.setId("signer-1");
        ESignatureRecipientRequest copy = recipient("Records", "records@example.com", 1);
        copy.setId("copy-1");
        copy.setRole(stirling.software.SPDF.model.signing.SigningRecipient.Role.CC);
        request.setRecipients(List.of(signer, copy));
        request.setFields(List.of(signatureField("signer-1", 0.5, 0.75, 0.35, 0.1)));

        ESignatureRequestView created = service.createRequest(pdfFile(), request, actor);
        String signerToken =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor)
                        .getNotifications()
                        .getFirst()
                        .getToken();
        ESignatureActionResponse completed =
                service.sign(signerToken, signRequest("Signer"), actor);

        assertEquals(2, completed.getNotifications().size());
        assertTrue(
                completed.getNotifications().stream()
                        .anyMatch(
                                notification ->
                                        "owner@example.com".equals(notification.getRecipientEmail())
                                                && notification.getToken() == null));
        ESignatureNotification copyNotification =
                completed.getNotifications().stream()
                        .filter(
                                notification ->
                                        "records@example.com"
                                                .equals(notification.getRecipientEmail()))
                        .findFirst()
                        .orElseThrow();
        assertNotNull(copyNotification.getToken());
        assertNotNull(service.getDocumentForToken(copyNotification.getToken(), null).getPath());
        ResponseStatusException copyCannotSign =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                service.sign(
                                        copyNotification.getToken(),
                                        signRequest("Records"),
                                        actor));
        assertEquals(HttpStatus.CONFLICT, copyCannotSign.getStatusCode());
    }

    @Test
    void persistsAndProcessesWebhookOutboxWithoutBlockingWorkflow() throws Exception {
        SsrfProtectionService ssrf = mock(SsrfProtectionService.class);
        when(ssrf.isUrlAllowed("https://hooks.example/signing")).thenReturn(true);
        AtomicInteger deliveries = new AtomicInteger();
        ESignatureWebhookService webhookService =
                new ESignatureWebhookService(
                        ssrf,
                        (uri, eventId, payload) -> {
                            deliveries.incrementAndGet();
                            assertTrue(payload.contains(eventId));
                            return 204;
                        });
        service =
                new ESignatureWorkflowService(
                        JsonMapper.builder().build(),
                        tempDir,
                        new ESignaturePdfService(),
                        webhookService);
        ESignatureCreateRequest request = orderedCreateRequest();
        request.setCallbackUrl("https://hooks.example/signing");

        ESignatureRequestView created = service.createRequest(pdfFile(), request, actor);
        var processed = service.processDueWebhooks(100, actor);

        assertEquals(4, processed.size());
        assertEquals(4, deliveries.get());
        assertTrue(
                processed.stream()
                        .allMatch(
                                delivery ->
                                        delivery.getStatus() == WebhookDeliveryStatus.DELIVERED));
        String metadata =
                Files.readString(tempDir.resolve(created.getId()).resolve("metadata.json"));
        assertTrue(metadata.contains("\"status\":\"DELIVERED\""));
        assertFalse(metadata.contains("https://hooks.example/signing\",\"eventId\""));
    }

    @Test
    void schedulesFailedWebhooksForRetry() throws Exception {
        SsrfProtectionService ssrf = mock(SsrfProtectionService.class);
        when(ssrf.isUrlAllowed("https://hooks.example/signing")).thenReturn(true);
        ESignatureWebhookService webhookService =
                new ESignatureWebhookService(ssrf, (uri, eventId, payload) -> 503);
        service =
                new ESignatureWorkflowService(
                        JsonMapper.builder().build(),
                        tempDir,
                        new ESignaturePdfService(),
                        webhookService);
        ESignatureCreateRequest request = orderedCreateRequest();
        request.setCallbackUrl("https://hooks.example/signing");
        service.createRequest(pdfFile(), request, actor);

        var processed = service.processDueWebhooks(1, actor);

        assertEquals(1, processed.size());
        assertEquals(WebhookDeliveryStatus.RETRYING, processed.getFirst().getStatus());
        assertEquals(1, processed.getFirst().getAttemptCount());
        assertNotNull(processed.getFirst().getNextAttemptAt());
        assertEquals(503, processed.getFirst().getLastStatusCode());
    }

    @Test
    void deliversSigningLinksThroughTheSelectedProvider() throws Exception {
        List<SigningNotificationMessage> sentMessages = new ArrayList<>();
        SigningNotificationProvider smsProvider =
                new SigningNotificationProvider() {
                    @Override
                    public String channel() {
                        return "sms";
                    }

                    @Override
                    public void send(SigningNotificationMessage message) {
                        sentMessages.add(message);
                    }
                };
        service =
                new ESignatureWorkflowService(
                        JsonMapper.builder().build(),
                        tempDir,
                        new ESignaturePdfService(),
                        null,
                        List.of(smsProvider));
        ESignatureCreateRequest request = orderedCreateRequest();
        ESignatureRecipientRequest first = request.getRecipients().getFirst();
        first.setDeliveryChannel(DeliveryChannel.SMS);
        first.setPhoneNumber("+23051234567");

        ESignatureRequestView created = service.createRequest(pdfFile(), request, actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);

        assertEquals("DELIVERED", sent.getNotifications().getFirst().getDeliveryStatus());
        assertEquals(1, sentMessages.size());
        assertEquals("+23051234567", sentMessages.getFirst().destination());
        assertTrue(sentMessages.getFirst().body().contains("/sign-request/"));
    }

    @Test
    void rejectsInvalidSmsRecipientPhoneNumber() throws Exception {
        ESignatureCreateRequest request = orderedCreateRequest();
        request.getRecipients().getFirst().setDeliveryChannel(DeliveryChannel.SMS);
        request.getRecipients().getFirst().setPhoneNumber("12345");

        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.createRequest(pdfFile(), request, actor));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void archivesTerminalWorkflowBeforePermanentDeletion() throws Exception {
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        ESignatureRecipientRequest recipient = recipient("Signer", "signer@example.com", 1);
        recipient.setId("recipient-1");
        request.setRecipients(List.of(recipient));
        request.setFields(List.of(signatureField("recipient-1", 0.5, 0.75, 0.35, 0.1)));
        ESignatureRequestView created = service.createRequest(pdfFile(), request, actor);
        String token =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor)
                        .getNotifications()
                        .getFirst()
                        .getToken();
        service.sign(token, signRequest("Signer"), actor);

        ESignatureRequestView archived = service.archive(created.getId(), actor);

        assertEquals(WorkflowStatus.ARCHIVED, archived.getStatus());
        assertNotNull(archived.getArchivedAt());
        assertTrue(Files.exists(tempDir.resolve(created.getId())));
        service.delete(created.getId(), actor);
        assertFalse(Files.exists(tempDir.resolve(created.getId())));
        assertThrows(
                ResponseStatusException.class, () -> service.getRequest(created.getId(), actor));
    }

    @Test
    void refusesToArchiveAnActiveWorkflow() throws Exception {
        ESignatureRequestView created =
                service.createRequest(pdfFile(), orderedCreateRequest(), actor);

        ResponseStatusException error =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.archive(created.getId(), actor));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void generatesVerifiableEvidenceCertificateAndDetectsAuditTampering() throws Exception {
        ESignatureRequestView created =
                service.createRequest(pdfFile(), orderedCreateRequest(), actor);

        var evidence = service.getEvidence(created.getId(), actor);

        assertTrue(evidence.isAuditIntegrityValid());
        assertNotNull(evidence.getAuditRootHash());
        assertEquals(64, evidence.getDocumentSha256().length());
        byte[] report = service.generateEvidencePdf(created.getId(), actor);
        try (PDDocument document = Loader.loadPDF(report)) {
            assertTrue(document.getNumberOfPages() >= 1);
        }

        Path metadataPath = tempDir.resolve(created.getId()).resolve("metadata.json");
        String metadata = Files.readString(metadataPath);
        Files.writeString(
                metadataPath,
                metadata.replace(
                        "E-signature request created", "E-signature request silently changed"));

        assertFalse(service.getEvidence(created.getId(), actor).isAuditIntegrityValid());
    }

    @Test
    void signerCompletionUpdatesTheStoredPdfRevision() throws Exception {
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        ESignatureRecipientRequest recipient = recipient("Signer", "signer@example.com", 1);
        recipient.setId("recipient-1");
        request.setRecipients(List.of(recipient));
        request.setFields(List.of(signatureField("recipient-1", 0.5, 0.75, 0.35, 0.1)));

        ESignatureRequestView created = service.createRequest(pdfFile(), request, actor);
        ESignatureActionResponse sent =
                service.sendRequest(created.getId(), new ESignatureSendRequest(), actor);
        ESignatureActionResponse signed =
                service.sign(
                        sent.getNotifications().getFirst().getToken(),
                        signRequest("Visible Signer"),
                        actor);

        assertEquals(1, signed.getRequest().getDocumentRevision());
        assertEquals("Visible Signer", signed.getRequest().getFields().getFirst().getValue());
        assertNotNull(signed.getRequest().getFields().getFirst().getCompletedAt());
        assertEquals(
                AuditEventType.DOCUMENT_UPDATED,
                service.getAuditTrail(created.getId()).stream()
                        .filter(event -> event.getType() == AuditEventType.DOCUMENT_UPDATED)
                        .findFirst()
                        .orElseThrow()
                        .getType());
    }

    private ESignatureCreateRequest orderedCreateRequest() {
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        request.setTitle("Mutual NDA");
        request.setMessage("Please review and sign.");
        request.setSigningOrder(true);
        ESignatureRecipientRequest first = recipient("First", "first@example.com", 1);
        first.setId("first-recipient");
        ESignatureRecipientRequest second = recipient("Second", "second@example.com", 2);
        second.setId("second-recipient");
        request.setRecipients(List.of(first, second));
        request.setFields(
                List.of(
                        signatureField("first-recipient", 0.1, 0.7, 0.35, 0.08),
                        signatureField("second-recipient", 0.1, 0.82, 0.35, 0.08)));
        return request;
    }

    private ESignatureRecipientRequest recipient(String name, String email, int signingOrder) {
        ESignatureRecipientRequest recipient = new ESignatureRecipientRequest();
        recipient.setName(name);
        recipient.setEmail(email);
        recipient.setSigningOrder(signingOrder);
        return recipient;
    }

    private ESignatureSignRequest signRequest(String name) {
        ESignatureSignRequest request = new ESignatureSignRequest();
        request.setSignerName(name);
        request.setConsentAccepted(true);
        request.setConsentText("I agree to sign electronically.");
        request.setSignatureType("typed");
        return request;
    }

    private SigningField signatureField(
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

    private MockMultipartFile pdfFile() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(output);
        }
        return new MockMultipartFile(
                "file", "contract.pdf", "application/pdf", output.toByteArray());
    }
}
