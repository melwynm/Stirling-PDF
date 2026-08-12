package stirling.software.SPDF.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import io.github.pixee.security.Filenames;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.esign.ESignatureActionResponse;
import stirling.software.SPDF.model.api.esign.ESignatureAuditEventView;
import stirling.software.SPDF.model.api.esign.ESignatureCancelRequest;
import stirling.software.SPDF.model.api.esign.ESignatureCreateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureDeclineRequest;
import stirling.software.SPDF.model.api.esign.ESignatureDueReminder;
import stirling.software.SPDF.model.api.esign.ESignatureNotification;
import stirling.software.SPDF.model.api.esign.ESignatureRecipientRequest;
import stirling.software.SPDF.model.api.esign.ESignatureRecipientView;
import stirling.software.SPDF.model.api.esign.ESignatureReminderRequest;
import stirling.software.SPDF.model.api.esign.ESignatureRequestView;
import stirling.software.SPDF.model.api.esign.ESignatureSendRequest;
import stirling.software.SPDF.model.api.esign.ESignatureSignRequest;
import stirling.software.SPDF.model.api.esign.ESignatureTokenContext;
import stirling.software.SPDF.model.esign.ESignatureWorkflow;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.AuditEvent;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.AuditEventType;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WebhookDelivery;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WebhookDeliveryStatus;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WorkflowStatus;
import stirling.software.SPDF.model.signing.SigningField;
import stirling.software.SPDF.model.signing.SigningModelValidator;
import stirling.software.SPDF.model.signing.SigningModelValidator.ValidationIssue;
import stirling.software.SPDF.model.signing.SigningRecipient;
import stirling.software.SPDF.model.signing.SigningRecipient.Authentication;
import stirling.software.SPDF.model.signing.SigningRecipient.DeliveryChannel;
import stirling.software.SPDF.model.signing.SigningRecipient.Method;
import stirling.software.SPDF.model.signing.SigningRecipient.Status;
import stirling.software.common.configuration.InstallationPathConfig;
import stirling.software.common.service.SigningNotificationProvider;
import stirling.software.common.service.SigningNotificationProvider.SigningNotificationMessage;
import stirling.software.common.util.RegexPatternUtils;

import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class ESignatureWorkflowService {

    private static final String METADATA_FILE = "metadata.json";
    private static final String DOCUMENT_FILE = "document.pdf";
    private static final int DEFAULT_REMINDER_INTERVAL_HOURS = 48;
    private static final int MAX_AUTHENTICATION_ATTEMPTS = 5;
    private static final long MAX_DOCUMENT_BYTES = 100L * 1024 * 1024 * 1024;
    private static final Duration DEFAULT_RETENTION_DURATION = Duration.ofDays(365);
    private static final Duration AUTHENTICATION_LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_WEBHOOK_ATTEMPTS = 8;
    private static final int MAX_NOTIFICATION_ATTEMPTS = 3;
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9-]+$");
    private static final Pattern PDF_EXTENSION_PATTERN = Pattern.compile("(?i).*\\.pdf$");

    private final ObjectMapper objectMapper;
    private final ESignaturePdfService pdfService;
    private final ESignatureWebhookService webhookService;
    private final Map<String, SigningNotificationProvider> notificationProviders;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Path requestsPath;

    @Autowired
    public ESignatureWorkflowService(
            ObjectMapper objectMapper,
            ESignaturePdfService pdfService,
            ESignatureWebhookService webhookService,
            List<SigningNotificationProvider> notificationProviders) {
        this(
                objectMapper,
                Paths.get(
                        InstallationPathConfig.getCustomFilesPath(),
                        "e-signature-workflows",
                        "requests"),
                pdfService,
                webhookService,
                notificationProviders);
    }

    ESignatureWorkflowService(ObjectMapper objectMapper, Path requestsPath) {
        this(objectMapper, requestsPath, new ESignaturePdfService(), null, List.of());
    }

    ESignatureWorkflowService(
            ObjectMapper objectMapper, Path requestsPath, ESignaturePdfService pdfService) {
        this(objectMapper, requestsPath, pdfService, null, List.of());
    }

    ESignatureWorkflowService(
            ObjectMapper objectMapper,
            Path requestsPath,
            ESignaturePdfService pdfService,
            ESignatureWebhookService webhookService) {
        this(objectMapper, requestsPath, pdfService, webhookService, List.of());
    }

    ESignatureWorkflowService(
            ObjectMapper objectMapper,
            Path requestsPath,
            ESignaturePdfService pdfService,
            ESignatureWebhookService webhookService,
            List<SigningNotificationProvider> notificationProviders) {
        this.objectMapper = objectMapper;
        this.requestsPath = requestsPath;
        this.pdfService = pdfService;
        this.webhookService = webhookService;
        this.notificationProviders =
                notificationProviders.stream()
                        .collect(
                                java.util.stream.Collectors.toUnmodifiableMap(
                                        provider -> provider.channel().toLowerCase(Locale.ROOT),
                                        provider -> provider,
                                        (first, ignored) -> first));
    }

    public ESignatureRequestView createRequest(
            MultipartFile file, ESignatureCreateRequest request, ActorContext actor)
            throws IOException {
        if (request == null) {
            request = new ESignatureCreateRequest();
        }
        ActorContext requestActor = actorOrSystem(actor);
        validatePdfFile(file);
        validateCreateRequest(request);

        Instant now = Instant.now();
        String requestId = UUID.randomUUID().toString();
        String originalFilename = sanitizeOriginalFilename(file.getOriginalFilename());

        ESignatureWorkflow workflow = new ESignatureWorkflow();
        workflow.setId(requestId);
        workflow.setOwnerId(requestActor.principalId());
        workflow.setTitle(defaultIfBlank(request.getTitle(), originalFilename));
        workflow.setMessage(request.getMessage());
        workflow.setRequesterName(request.getRequesterName());
        workflow.setRequesterEmail(request.getRequesterEmail());
        workflow.setOriginalFilename(originalFilename);
        workflow.setDocumentFileName(DOCUMENT_FILE);
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflow.setExpiresAt(request.getExpiresAt());
        workflow.setRetentionUntil(now.plus(DEFAULT_RETENTION_DURATION));
        workflow.setPublicBaseUrl(request.getPublicBaseUrl());
        workflow.setCallbackUrl(request.getCallbackUrl());
        workflow.setSigningOrder(request.isSigningOrder());
        workflow.setRemindersEnabled(request.isRemindersEnabled());
        workflow.setReminderIntervalHours(
                request.getReminderIntervalHours() == null
                        ? DEFAULT_REMINDER_INTERVAL_HOURS
                        : Math.max(1, request.getReminderIntervalHours()));

        int fallbackOrder = 1;
        for (ESignatureRecipientRequest recipientRequest : request.getRecipients()) {
            SigningRecipient recipient = new SigningRecipient();
            recipient.setId(requestedOrGeneratedId(recipientRequest.getId(), "recipient"));
            recipient.setName(recipientRequest.getName().trim());
            recipient.setEmail(recipientRequest.getEmail().trim().toLowerCase(Locale.ROOT));
            recipient.setPhoneNumber(recipientRequest.getPhoneNumber());
            recipient.setDeliveryChannel(
                    recipientRequest.getDeliveryChannel() == null
                            ? DeliveryChannel.EMAIL
                            : recipientRequest.getDeliveryChannel());
            recipient.setRole(recipientRequest.getRole());
            recipient.setSigningOrder(
                    recipientRequest.getSigningOrder() == null
                            ? fallbackOrder
                            : Math.max(1, recipientRequest.getSigningOrder()));
            recipient.setCreatedAt(now);
            Authentication authentication = new Authentication();
            Method authenticationMethod =
                    recipientRequest.getAuthenticationMethod() == null
                            ? Method.EMAIL_LINK
                            : recipientRequest.getAuthenticationMethod();
            authentication.setMethod(authenticationMethod);
            if (authenticationMethod == Method.ACCESS_CODE) {
                authentication.setAccessCodeHash(
                        SigningAccessCodeHasher.hash(recipientRequest.getAccessCode()));
            }
            recipient.setAuthentication(authentication);
            recipient.setMetadata(copyStringMap(recipientRequest.getMetadata()));
            workflow.getRecipients().add(recipient);
            fallbackOrder++;
        }
        workflow.setFields(prepareSigningFields(request.getFields(), workflow.getRecipients()));
        validateSigningModel(workflow.getRecipients(), workflow.getFields());

        Path requestPath = requestPath(requestId);
        Files.createDirectories(requestPath);
        Path documentPath = requestPath.resolve(DOCUMENT_FILE);
        verifyWithin(requestPath, documentPath);
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, documentPath, StandardCopyOption.REPLACE_EXISTING);
        }

        addAudit(
                workflow,
                AuditEventType.REQUEST_CREATED,
                null,
                requestActor.withFallback(request.getRequesterName(), request.getRequesterEmail()),
                "E-signature request created",
                Map.of("title", workflow.getTitle()));
        addAudit(
                workflow,
                AuditEventType.DOCUMENT_UPLOADED,
                null,
                requestActor,
                "Document uploaded for e-signature workflow",
                Map.of("filename", originalFilename, "storedFilename", DOCUMENT_FILE));
        for (SigningRecipient recipient : workflow.getRecipients()) {
            addAudit(
                    workflow,
                    AuditEventType.RECIPIENT_ADDED,
                    recipient.getId(),
                    requestActor,
                    "Recipient added",
                    Map.of("email", recipient.getEmail(), "name", recipient.getName()));
        }

        saveWorkflow(workflow);
        return toView(workflow);
    }

    public List<ESignatureRequestView> listRequests(String status) throws IOException {
        return listRequests(status, null);
    }

    public List<ESignatureRequestView> listRequests(String status, ActorContext actor)
            throws IOException {
        WorkflowStatus requestedStatus = parseWorkflowStatus(status);
        return listWorkflows().stream()
                .filter(workflow -> isOwnedBy(workflow, actor))
                .filter(
                        workflow ->
                                requestedStatus == null || workflow.getStatus() == requestedStatus)
                .sorted(Comparator.comparing(ESignatureWorkflow::getCreatedAt).reversed())
                .map(this::toView)
                .toList();
    }

    public ESignatureRequestView getRequest(String requestId) throws IOException {
        return toView(loadWorkflow(requestId));
    }

    public ESignatureRequestView getRequest(String requestId, ActorContext actor)
            throws IOException {
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        return toView(workflow);
    }

    public List<ESignatureAuditEventView> getAuditTrail(String requestId) throws IOException {
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        return workflow.getAuditTrail().stream().map(this::toAuditView).toList();
    }

    public List<ESignatureAuditEventView> getAuditTrail(String requestId, ActorContext actor)
            throws IOException {
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        return workflow.getAuditTrail().stream().map(this::toAuditView).toList();
    }

    public List<ESignatureAuditEventView> listEvents(Instant since, String type)
            throws IOException {
        return listEvents(since, type, null);
    }

    public List<ESignatureAuditEventView> listEvents(Instant since, String type, ActorContext actor)
            throws IOException {
        AuditEventType eventType = parseAuditEventType(type);
        return listWorkflows().stream()
                .filter(workflow -> isOwnedBy(workflow, actor))
                .flatMap(workflow -> workflow.getAuditTrail().stream())
                .filter(event -> since == null || event.getTimestamp().isAfter(since))
                .filter(event -> eventType == null || event.getType() == eventType)
                .sorted(Comparator.comparing(AuditEvent::getTimestamp))
                .map(this::toAuditView)
                .toList();
    }

    public List<WebhookDelivery> processDueWebhooks(int requestedLimit, ActorContext actor)
            throws IOException {
        if (webhookService == null) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        Instant now = Instant.now();
        List<WebhookDelivery> processed = new ArrayList<>();
        for (ESignatureWorkflow workflow : listWorkflows()) {
            if (!isOwnedBy(workflow, actor)) {
                continue;
            }
            boolean changed = false;
            for (WebhookDelivery delivery : workflow.getWebhookDeliveries()) {
                if (processed.size() >= limit) {
                    break;
                }
                if (!isWebhookDue(delivery, now)) {
                    continue;
                }
                attemptWebhook(workflow, delivery, now);
                processed.add(delivery);
                changed = true;
            }
            if (changed) {
                saveWorkflow(workflow);
            }
            if (processed.size() >= limit) {
                break;
            }
        }
        return processed;
    }

    public List<WebhookDelivery> retryWebhooks(String requestId, ActorContext actor)
            throws IOException {
        if (webhookService == null) {
            return List.of();
        }
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        Instant now = Instant.now();
        List<WebhookDelivery> retried = new ArrayList<>();
        for (WebhookDelivery delivery : workflow.getWebhookDeliveries()) {
            if (delivery.getStatus() != WebhookDeliveryStatus.FAILED) {
                continue;
            }
            delivery.setStatus(WebhookDeliveryStatus.RETRYING);
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(now);
            delivery.setLastError(null);
            attemptWebhook(workflow, delivery, now);
            retried.add(delivery);
        }
        saveWorkflow(workflow);
        return retried;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void processWebhookOutbox() {
        try {
            processDueWebhooks(100, ActorContext.system());
        } catch (IOException | RuntimeException e) {
            log.warn("Unable to process the e-signature webhook outbox", e);
        }
    }

    public ESignatureActionResponse sendRequest(
            String requestId, ESignatureSendRequest request, ActorContext actor)
            throws IOException {
        if (request == null) {
            request = new ESignatureSendRequest();
        }
        actor = actorOrSystem(actor);
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        ensureWorkflowCanContinue(workflow);
        ensureSignerFieldsAssigned(workflow);

        Instant now = Instant.now();
        if (StringUtils.hasText(request.getPublicBaseUrl())) {
            workflow.setPublicBaseUrl(request.getPublicBaseUrl());
        }
        if (StringUtils.hasText(request.getMessage())) {
            workflow.setMessage(request.getMessage());
        }

        List<SigningRecipient> recipients =
                recipientsToNotify(workflow, request.getRecipientIds(), false);
        if (recipients.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No recipients are currently eligible to be sent");
        }

        workflow.setStatus(WorkflowStatus.SENT);
        workflow.setSentAt(workflow.getSentAt() == null ? now : workflow.getSentAt());
        workflow.setUpdatedAt(now);
        addAudit(
                workflow,
                AuditEventType.REQUEST_SENT,
                null,
                actor,
                "E-signature request sent",
                Map.of("recipientCount", String.valueOf(recipients.size())));

        List<ESignatureNotification> notifications = new ArrayList<>();
        for (SigningRecipient recipient : recipients) {
            recipient.setStatus(Status.SENT);
            recipient.setSentAt(recipient.getSentAt() == null ? now : recipient.getSentAt());
            ESignatureNotification notification =
                    createNotification(
                            workflow,
                            recipient,
                            request.getPublicBaseUrl(),
                            request.getMessage(),
                            request.isRotateTokens(),
                            "signature-request");
            notifications.add(notification);
            addAudit(
                    workflow,
                    AuditEventType.RECIPIENT_SENT,
                    recipient.getId(),
                    actor,
                    "Recipient signing link issued",
                    Map.of("email", recipient.getEmail()));
        }

        saveWorkflow(workflow);
        return actionResponse(workflow, notifications);
    }

    public List<ESignatureDueReminder> getDueReminders() throws IOException {
        Instant now = Instant.now();
        List<ESignatureDueReminder> dueReminders = new ArrayList<>();
        for (ESignatureWorkflow workflow : listWorkflows()) {
            if (!isActiveWorkflow(workflow)) {
                continue;
            }
            for (SigningRecipient recipient : recipientsToNotify(workflow, List.of(), true)) {
                Optional<Instant> dueAt = reminderDueAt(workflow, recipient);
                if (dueAt.isPresent() && !dueAt.get().isAfter(now)) {
                    ESignatureDueReminder reminder = new ESignatureDueReminder();
                    reminder.setRequestId(workflow.getId());
                    reminder.setRecipientId(recipient.getId());
                    reminder.setRecipientName(recipient.getName());
                    reminder.setRecipientEmail(recipient.getEmail());
                    reminder.setDueAt(dueAt.get());
                    reminder.setReminderCount(recipient.getReminderCount());
                    dueReminders.add(reminder);
                }
            }
        }
        return dueReminders;
    }

    public List<ESignatureNotification> sendDueReminders(
            ESignatureReminderRequest request, ActorContext actor) throws IOException {
        if (request == null) {
            request = new ESignatureReminderRequest();
        }
        actor = actorOrSystem(actor);
        Instant now = Instant.now();
        List<ESignatureNotification> notifications = new ArrayList<>();
        for (ESignatureWorkflow workflow : listWorkflows()) {
            if (!isActiveWorkflow(workflow) || !workflow.isRemindersEnabled()) {
                continue;
            }
            List<SigningRecipient> recipients =
                    recipientsToNotify(workflow, request.getRecipientIds(), true);
            boolean changed = false;
            for (SigningRecipient recipient : recipients) {
                Optional<Instant> dueAt = reminderDueAt(workflow, recipient);
                if (request.isOnlyDue() && (dueAt.isEmpty() || dueAt.get().isAfter(now))) {
                    continue;
                }
                recipient.setLastReminderAt(now);
                recipient.setReminderCount(recipient.getReminderCount() + 1);
                ESignatureNotification notification =
                        createNotification(
                                workflow,
                                recipient,
                                request.getPublicBaseUrl(),
                                request.getMessage(),
                                false,
                                "signature-reminder");
                notifications.add(notification);
                addAudit(
                        workflow,
                        AuditEventType.REMINDER_SENT,
                        recipient.getId(),
                        actor,
                        "Reminder notification issued",
                        Map.of(
                                "email",
                                recipient.getEmail(),
                                "reminderCount",
                                String.valueOf(recipient.getReminderCount())));
                changed = true;
            }
            if (changed) {
                workflow.setUpdatedAt(now);
                saveWorkflow(workflow);
            }
        }
        return notifications;
    }

    public ESignatureActionResponse sendReminders(
            String requestId, ESignatureReminderRequest request, ActorContext actor)
            throws IOException {
        if (request == null) {
            request = new ESignatureReminderRequest();
        }
        actor = actorOrSystem(actor);
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        ensureWorkflowCanContinue(workflow);
        if (!workflow.isRemindersEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Reminders are disabled for this request");
        }

        Instant now = Instant.now();
        List<SigningRecipient> recipients =
                recipientsToNotify(workflow, request.getRecipientIds(), true);
        if (recipients.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No recipients are currently eligible for reminders");
        }

        List<ESignatureNotification> notifications = new ArrayList<>();
        for (SigningRecipient recipient : recipients) {
            Optional<Instant> dueAt = reminderDueAt(workflow, recipient);
            if (request.isOnlyDue() && (dueAt.isEmpty() || dueAt.get().isAfter(now))) {
                continue;
            }
            recipient.setLastReminderAt(now);
            recipient.setReminderCount(recipient.getReminderCount() + 1);
            ESignatureNotification notification =
                    createNotification(
                            workflow,
                            recipient,
                            request.getPublicBaseUrl(),
                            request.getMessage(),
                            false,
                            "signature-reminder");
            notifications.add(notification);
            addAudit(
                    workflow,
                    AuditEventType.REMINDER_SENT,
                    recipient.getId(),
                    actor,
                    "Reminder notification issued",
                    Map.of(
                            "email",
                            recipient.getEmail(),
                            "reminderCount",
                            String.valueOf(recipient.getReminderCount())));
        }

        if (notifications.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No reminders are due");
        }
        workflow.setUpdatedAt(now);
        saveWorkflow(workflow);
        return actionResponse(workflow, notifications);
    }

    public ESignatureTokenContext getTokenContext(String token) throws IOException {
        TokenResolution resolution = resolveToken(token);
        ESignatureTokenContext context = new ESignatureTokenContext();
        ESignatureRequestView requestView = toView(resolution.workflow());
        requestView.setRecipients(List.of(toRecipientView(resolution.recipient())));
        requestView.setFields(
                requestView.getFields().stream()
                        .filter(
                                field ->
                                        Objects.equals(
                                                field.getRecipientId(),
                                                resolution.recipient().getId()))
                        .toList());
        context.setRequest(requestView);
        context.setRecipient(toRecipientView(resolution.recipient()));
        context.setDownloadUrl("/api/v1/security/e-sign/recipients/" + token + "/download");
        return context;
    }

    public ESignatureActionResponse markViewed(String token, String accessCode, ActorContext actor)
            throws IOException {
        actor = actorOrSystem(actor);
        TokenResolution resolution = resolveToken(token);
        ESignatureWorkflow workflow = resolution.workflow();
        SigningRecipient recipient = resolution.recipient();
        verifyRecipientAuthentication(workflow, recipient, accessCode);
        ensureWorkflowCanContinue(workflow);
        ensureRecipientCanAct(recipient);

        Instant now = Instant.now();
        if (recipient.getStatus() == Status.SENT || recipient.getStatus() == Status.PENDING) {
            recipient.setStatus(Status.VIEWED);
        }
        recipient.setViewedAt(recipient.getViewedAt() == null ? now : recipient.getViewedAt());
        workflow.setStatus(WorkflowStatus.IN_PROGRESS);
        workflow.setUpdatedAt(now);
        addAudit(
                workflow,
                AuditEventType.RECIPIENT_VIEWED,
                recipient.getId(),
                actor,
                "Recipient viewed signing request",
                Map.of("email", recipient.getEmail()));
        saveWorkflow(workflow);
        return actionResponse(workflow, List.of());
    }

    public ESignatureActionResponse sign(
            String token, ESignatureSignRequest request, ActorContext actor) throws IOException {
        if (request == null || !request.isConsentAccepted()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The signer must accept the consent statement");
        }
        actor = actorOrSystem(actor);
        TokenResolution resolution = resolveToken(token);
        ESignatureWorkflow workflow = resolution.workflow();
        SigningRecipient recipient = resolution.recipient();
        verifyRecipientAuthentication(workflow, recipient, request.getAccessCode());
        ensureWorkflowCanContinue(workflow);
        ensureRecipientCanAct(recipient);

        if (workflow.isSigningOrder() && !isRecipientInActiveOrder(workflow, recipient)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This recipient is not next in the signing order");
        }

        Instant now = Instant.now();
        Path documentPath = requestPath(workflow.getId()).resolve(workflow.getDocumentFileName());
        ESignaturePdfService.AppliedRevision appliedRevision =
                pdfService.applyRecipientFields(
                        documentPath, workflow.getFields(), recipient, request, now);
        if (appliedRevision.fieldCount() > 0) {
            workflow.setDocumentRevision(workflow.getDocumentRevision() + 1);
            addAudit(
                    workflow,
                    AuditEventType.DOCUMENT_UPDATED,
                    recipient.getId(),
                    actor,
                    "Recipient fields applied to PDF revision",
                    Map.of(
                            "revision",
                            String.valueOf(workflow.getDocumentRevision()),
                            "fieldCount",
                            String.valueOf(appliedRevision.fieldCount()),
                            "beforeSha256",
                            appliedRevision.beforeSha256(),
                            "afterSha256",
                            appliedRevision.afterSha256()));
        }
        recipient.setStatus(Status.SIGNED);
        recipient.setSignedAt(now);
        recipient.setSignerName(defaultIfBlank(request.getSignerName(), recipient.getName()));
        recipient.setSignatureType(defaultIfBlank(request.getSignatureType(), "typed"));
        recipient.setSignatureDataUrl(request.getSignatureDataUrl());
        recipient.setConsentText(
                defaultIfBlank(request.getConsentText(), "Signer consent accepted"));
        recipient.setMetadata(mergeStringMaps(recipient.getMetadata(), request.getMetadata()));
        workflow.setUpdatedAt(now);

        ActorContext signerActor =
                actor.withFallback(recipient.getName(), recipient.getEmail())
                        .withNetworkFallback(request.getIpAddress(), request.getUserAgent());
        addAudit(
                workflow,
                AuditEventType.RECIPIENT_SIGNED,
                recipient.getId(),
                signerActor,
                "Recipient completed signature step",
                Map.of(
                        "email",
                        recipient.getEmail(),
                        "signatureType",
                        recipient.getSignatureType(),
                        "consentAccepted",
                        "true"));

        List<ESignatureNotification> notifications = new ArrayList<>();
        if (allRecipientsSigned(workflow)) {
            workflow.setStatus(WorkflowStatus.COMPLETED);
            workflow.setCompletedAt(now);
            addAudit(
                    workflow,
                    AuditEventType.REQUEST_COMPLETED,
                    null,
                    signerActor,
                    "All recipients signed the request",
                    Map.of("recipientCount", String.valueOf(workflow.getRecipients().size())));
            notifications.addAll(createCompletionNotifications(workflow));
        } else {
            workflow.setStatus(WorkflowStatus.IN_PROGRESS);
            notifications.addAll(activateNextOrderedRecipients(workflow, signerActor));
        }

        saveWorkflow(workflow);
        return actionResponse(workflow, notifications);
    }

    public ESignatureActionResponse decline(
            String token, ESignatureDeclineRequest request, ActorContext actor) throws IOException {
        if (request == null) {
            request = new ESignatureDeclineRequest();
        }
        actor = actorOrSystem(actor);
        TokenResolution resolution = resolveToken(token);
        ESignatureWorkflow workflow = resolution.workflow();
        SigningRecipient recipient = resolution.recipient();
        verifyRecipientAuthentication(workflow, recipient, request.getAccessCode());
        ensureWorkflowCanContinue(workflow);
        ensureRecipientCanAct(recipient);

        Instant now = Instant.now();
        recipient.setStatus(Status.DECLINED);
        recipient.setDeclinedAt(now);
        recipient.setDeclineReason(request.getReason());
        workflow.setStatus(WorkflowStatus.DECLINED);
        workflow.setDeclinedAt(now);
        workflow.setUpdatedAt(now);

        ActorContext signerActor =
                actor.withFallback(recipient.getName(), recipient.getEmail())
                        .withNetworkFallback(request.getIpAddress(), request.getUserAgent());
        addAudit(
                workflow,
                AuditEventType.RECIPIENT_DECLINED,
                recipient.getId(),
                signerActor,
                "Recipient declined signing request",
                Map.of(
                        "email",
                        recipient.getEmail(),
                        "reason",
                        defaultIfBlank(request.getReason(), "")));
        saveWorkflow(workflow);
        return actionResponse(workflow, List.of());
    }

    public ESignatureRequestView cancel(
            String requestId, ESignatureCancelRequest request, ActorContext actor)
            throws IOException {
        if (request == null) {
            request = new ESignatureCancelRequest();
        }
        actor = actorOrSystem(actor);
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        if (workflow.getStatus() == WorkflowStatus.COMPLETED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Completed signature requests cannot be cancelled");
        }
        if (workflow.getStatus() == WorkflowStatus.CANCELLED) {
            return toView(workflow);
        }

        Instant now = Instant.now();
        workflow.setStatus(WorkflowStatus.CANCELLED);
        workflow.setCancelledAt(now);
        workflow.setUpdatedAt(now);
        addAudit(
                workflow,
                AuditEventType.REQUEST_CANCELLED,
                null,
                actor,
                "E-signature request cancelled",
                Map.of("reason", defaultIfBlank(request.getReason(), "")));
        saveWorkflow(workflow);
        return toView(workflow);
    }

    public ESignatureRequestView archive(String requestId, ActorContext actor) throws IOException {
        actor = actorOrSystem(actor);
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        if (isActiveWorkflow(workflow)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Active signature requests must be cancelled first");
        }
        if (workflow.getStatus() == WorkflowStatus.ARCHIVED) {
            return toView(workflow);
        }
        workflow.setStatus(WorkflowStatus.ARCHIVED);
        workflow.setArchivedAt(Instant.now());
        workflow.setUpdatedAt(Instant.now());
        addAudit(
                workflow,
                AuditEventType.REQUEST_ARCHIVED,
                null,
                actor,
                "E-signature request archived",
                Map.of());
        saveWorkflow(workflow);
        return toView(workflow);
    }

    public void delete(String requestId, ActorContext actor) throws IOException {
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        if (workflow.getStatus() != WorkflowStatus.ARCHIVED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only archived signature requests can be deleted");
        }
        Path path = requestPath(requestId);
        verifyWithin(requestsPath, path);
        try (var files = Files.walk(path)) {
            for (Path candidate : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    public Path getDocumentPath(String requestId) throws IOException {
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        return workflowDocumentPath(workflow);
    }

    public Path getDocumentPath(String requestId, ActorContext actor) throws IOException {
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        return workflowDocumentPath(workflow);
    }

    public DocumentDownload getDocumentForToken(String token, String accessCode)
            throws IOException {
        TokenResolution resolution = resolveToken(token);
        verifyRecipientAuthentication(resolution.workflow(), resolution.recipient(), accessCode);
        Path documentPath = getDocumentPath(resolution.workflow().getId());
        return new DocumentDownload(documentPath, resolution.workflow().getOriginalFilename());
    }

    public String getOriginalFilename(String requestId) throws IOException {
        return loadWorkflow(requestId).getOriginalFilename();
    }

    public String getOriginalFilename(String requestId, ActorContext actor) throws IOException {
        ESignatureWorkflow workflow = loadWorkflow(requestId);
        ensureOwnedBy(workflow, actor);
        return workflow.getOriginalFilename();
    }

    private Path workflowDocumentPath(ESignatureWorkflow workflow) {
        Path documentPath = requestPath(workflow.getId()).resolve(workflow.getDocumentFileName());
        if (!Files.exists(documentPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow document not found");
        }
        return documentPath;
    }

    private ESignatureWorkflow loadWorkflow(String requestId) throws IOException {
        validateSafeId(requestId);
        Path metadataPath = metadataPath(requestId);
        if (!Files.exists(metadataPath)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "E-signature request not found");
        }
        ESignatureWorkflow workflow = readWorkflow(metadataPath);
        expireIfNeeded(workflow);
        return workflow;
    }

    private List<ESignatureWorkflow> listWorkflows() throws IOException {
        Files.createDirectories(requestsPath);
        try (var stream = Files.list(requestsPath)) {
            List<ESignatureWorkflow> workflows =
                    stream.filter(Files::isDirectory)
                            .map(path -> path.resolve(METADATA_FILE))
                            .filter(Files::exists)
                            .map(this::tryReadWorkflow)
                            .filter(Objects::nonNull)
                            .toList();
            for (ESignatureWorkflow workflow : workflows) {
                expireIfNeeded(workflow);
            }
            return workflows;
        }
    }

    private ESignatureWorkflow tryReadWorkflow(Path metadataPath) {
        try {
            return readWorkflow(metadataPath);
        } catch (IOException e) {
            log.warn("Failed to read e-signature workflow metadata at {}", metadataPath, e);
            return null;
        }
    }

    private ESignatureWorkflow readWorkflow(Path metadataPath) throws IOException {
        ESignatureWorkflow workflow =
                objectMapper.readValue(metadataPath.toFile(), ESignatureWorkflow.class);
        if (workflow.getModelVersion() < 1) {
            workflow.setModelVersion(ESignatureWorkflow.CURRENT_MODEL_VERSION);
        }
        if (workflow.getRecipients() == null) {
            workflow.setRecipients(new ArrayList<>());
        }
        for (SigningRecipient recipient : workflow.getRecipients()) {
            if (recipient.getAuthentication() == null) {
                recipient.setAuthentication(new Authentication());
            }
        }
        if (workflow.getFields() == null) {
            workflow.setFields(new ArrayList<>());
        }
        if (workflow.getAuditTrail() == null) {
            workflow.setAuditTrail(new ArrayList<>());
        }
        if (workflow.getWebhookDeliveries() == null) {
            workflow.setWebhookDeliveries(new ArrayList<>());
        }
        return workflow;
    }

    private void saveWorkflow(ESignatureWorkflow workflow) throws IOException {
        Files.createDirectories(requestPath(workflow.getId()));
        workflow.setUpdatedAt(
                workflow.getUpdatedAt() == null ? Instant.now() : workflow.getUpdatedAt());
        Path metadataPath = metadataPath(workflow.getId());
        Path tempFile = Files.createTempFile(requestPath(workflow.getId()), "metadata-", ".tmp");
        Files.write(tempFile, objectMapper.writeValueAsBytes(workflow));
        try {
            Files.move(
                    tempFile,
                    metadataPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, metadataPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void expireIfNeeded(ESignatureWorkflow workflow) throws IOException {
        if (workflow.getExpiresAt() == null
                || !isActiveWorkflow(workflow)
                || !Instant.now().isAfter(workflow.getExpiresAt())) {
            return;
        }
        workflow.setStatus(WorkflowStatus.EXPIRED);
        workflow.setUpdatedAt(Instant.now());
        for (SigningRecipient recipient : workflow.getRecipients()) {
            if (!isTerminalRecipient(recipient)) {
                recipient.setStatus(Status.EXPIRED);
            }
        }
        addAudit(
                workflow,
                AuditEventType.REQUEST_EXPIRED,
                null,
                ActorContext.system(),
                "E-signature request expired",
                Map.of("expiresAt", workflow.getExpiresAt().toString()));
        saveWorkflow(workflow);
    }

    private TokenResolution resolveToken(String token) throws IOException {
        if (!StringUtils.hasText(token)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing signing token");
        }
        for (ESignatureWorkflow workflow : listWorkflows()) {
            for (SigningRecipient recipient : workflow.getRecipients()) {
                if (tokenMatches(recipient, token)) {
                    if (workflow.getStatus() == WorkflowStatus.EXPIRED) {
                        throw new ResponseStatusException(
                                HttpStatus.GONE, "This signing request has expired");
                    }
                    if (workflow.getStatus() == WorkflowStatus.CANCELLED) {
                        throw new ResponseStatusException(
                                HttpStatus.GONE, "This signing request has been cancelled");
                    }
                    return new TokenResolution(workflow, recipient);
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Signing token not found");
    }

    private List<ESignatureNotification> activateNextOrderedRecipients(
            ESignatureWorkflow workflow, ActorContext actor) {
        if (!workflow.isSigningOrder()) {
            return List.of();
        }
        List<SigningRecipient> recipients = recipientsToNotify(workflow, List.of(), false);
        Instant now = Instant.now();
        List<ESignatureNotification> notifications = new ArrayList<>();
        for (SigningRecipient recipient : recipients) {
            if (recipient.getStatus() != Status.PENDING) {
                continue;
            }
            recipient.setStatus(Status.SENT);
            recipient.setSentAt(now);
            notifications.add(
                    createNotification(
                            workflow,
                            recipient,
                            workflow.getPublicBaseUrl(),
                            null,
                            false,
                            "signature-request"));
            addAudit(
                    workflow,
                    AuditEventType.RECIPIENT_SENT,
                    recipient.getId(),
                    actor,
                    "Next signing-order recipient activated",
                    Map.of("email", recipient.getEmail()));
        }
        return notifications;
    }

    private List<SigningRecipient> recipientsToNotify(
            ESignatureWorkflow workflow, List<String> recipientIds, boolean reminder) {
        List<SigningRecipient> candidates =
                workflow.getRecipients().stream()
                        .filter(recipient -> recipient.getRole() != SigningRecipient.Role.CC)
                        .filter(recipient -> !isTerminalRecipient(recipient))
                        .filter(
                                recipient ->
                                        CollectionUtils.isEmpty(recipientIds)
                                                || recipientIds.contains(recipient.getId()))
                        .filter(
                                recipient ->
                                        !reminder
                                                || recipient.getStatus() == Status.SENT
                                                || recipient.getStatus() == Status.VIEWED)
                        .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (!workflow.isSigningOrder()) {
            return candidates;
        }
        int activeOrder =
                workflow.getRecipients().stream()
                        .filter(recipient -> recipient.getRole() != SigningRecipient.Role.CC)
                        .filter(recipient -> !isTerminalRecipient(recipient))
                        .mapToInt(SigningRecipient::getSigningOrder)
                        .min()
                        .orElse(Integer.MAX_VALUE);
        return candidates.stream()
                .filter(recipient -> recipient.getSigningOrder() == activeOrder)
                .toList();
    }

    private Optional<Instant> reminderDueAt(
            ESignatureWorkflow workflow, SigningRecipient recipient) {
        if (!workflow.isRemindersEnabled()) {
            return Optional.empty();
        }
        Instant anchor =
                recipient.getLastReminderAt() != null
                        ? recipient.getLastReminderAt()
                        : recipient.getSentAt();
        if (anchor == null) {
            return Optional.empty();
        }
        int hours = Math.max(1, workflow.getReminderIntervalHours());
        return Optional.of(anchor.plus(Duration.ofHours(hours)));
    }

    private ESignatureNotification createNotification(
            ESignatureWorkflow workflow,
            SigningRecipient recipient,
            String publicBaseUrl,
            String message,
            boolean rotateToken,
            String eventType) {
        String signingToken = generateToken();
        recipient.setSigningTokenHash(hashToken(signingToken));
        recipient.setSigningToken(null);

        String baseUrl = defaultIfBlank(publicBaseUrl, workflow.getPublicBaseUrl());
        String signingPath = "/sign-request/" + signingToken;
        String signingUrl =
                StringUtils.hasText(baseUrl)
                        ? baseUrl.replaceAll("/+$", "") + signingPath
                        : signingPath;

        ESignatureNotification notification = new ESignatureNotification();
        notification.setRequestId(workflow.getId());
        notification.setRecipientId(recipient.getId());
        notification.setRecipientName(recipient.getName());
        notification.setRecipientEmail(recipient.getEmail());
        notification.setRecipientPhone(recipient.getPhoneNumber());
        notification.setDeliveryChannel(recipient.getDeliveryChannel().value());
        notification.setSubject(
                ("signature-reminder".equals(eventType) ? "Reminder: " : "")
                        + "Signature requested: "
                        + workflow.getTitle());
        notification.setMessage(defaultIfBlank(message, workflow.getMessage()));
        notification.setSigningUrl(signingUrl);
        notification.setToken(signingToken);
        notification.setEventType(eventType);
        dispatchNotification(notification);
        auditNotificationDelivery(workflow, recipient.getId(), notification);
        return notification;
    }

    private List<ESignatureNotification> createCompletionNotifications(
            ESignatureWorkflow workflow) {
        List<ESignatureNotification> notifications = new ArrayList<>();
        if (StringUtils.hasText(workflow.getRequesterEmail())) {
            ESignatureNotification requesterCopy = new ESignatureNotification();
            requesterCopy.setRequestId(workflow.getId());
            requesterCopy.setRecipientName(workflow.getRequesterName());
            requesterCopy.setRecipientEmail(workflow.getRequesterEmail());
            requesterCopy.setSubject("Completed: " + workflow.getTitle());
            requesterCopy.setMessage("All required recipients completed the signature request.");
            requesterCopy.setDocumentUrl(
                    "/api/v1/security/e-sign/requests/" + workflow.getId() + "/download");
            requesterCopy.setEventType("signature-completed");
            dispatchNotification(requesterCopy);
            auditNotificationDelivery(workflow, null, requesterCopy);
            notifications.add(requesterCopy);
        }
        for (SigningRecipient recipient : workflow.getRecipients()) {
            if (recipient.getRole() != SigningRecipient.Role.CC) {
                continue;
            }
            String token = generateToken();
            recipient.setSigningTokenHash(hashToken(token));
            recipient.setSigningToken(null);
            String documentPath = "/api/v1/security/e-sign/recipients/" + token + "/download";
            String baseUrl = workflow.getPublicBaseUrl();
            ESignatureNotification copy = new ESignatureNotification();
            copy.setRequestId(workflow.getId());
            copy.setRecipientId(recipient.getId());
            copy.setRecipientName(recipient.getName());
            copy.setRecipientEmail(recipient.getEmail());
            copy.setRecipientPhone(recipient.getPhoneNumber());
            copy.setDeliveryChannel(recipient.getDeliveryChannel().value());
            copy.setSubject("Completed: " + workflow.getTitle());
            copy.setMessage("The completed document is ready.");
            copy.setDocumentUrl(
                    StringUtils.hasText(baseUrl)
                            ? baseUrl.replaceAll("/+$", "") + documentPath
                            : documentPath);
            copy.setToken(token);
            copy.setEventType("signature-completed");
            dispatchNotification(copy);
            auditNotificationDelivery(workflow, recipient.getId(), copy);
            notifications.add(copy);
        }
        return notifications;
    }

    private void dispatchNotification(ESignatureNotification notification) {
        String channel = defaultIfBlank(notification.getDeliveryChannel(), "email");
        SigningNotificationProvider provider =
                notificationProviders.get(channel.toLowerCase(Locale.ROOT));
        if (provider == null) {
            notification.setDeliveryStatus("UNAVAILABLE");
            notification.setDeliveryError("No " + channel + " notification provider is configured");
            return;
        }
        String destination =
                "sms".equalsIgnoreCase(channel)
                        ? notification.getRecipientPhone()
                        : notification.getRecipientEmail();
        String actionUrl =
                StringUtils.hasText(notification.getSigningUrl())
                        ? notification.getSigningUrl()
                        : notification.getDocumentUrl();
        String body = defaultIfBlank(notification.getMessage(), "");
        if (StringUtils.hasText(actionUrl)) {
            body = body + System.lineSeparator() + System.lineSeparator() + actionUrl;
        }
        SigningNotificationMessage message =
                new SigningNotificationMessage(
                        destination,
                        notification.getRecipientName(),
                        notification.getSubject(),
                        body);
        for (int attempt = 1; attempt <= MAX_NOTIFICATION_ATTEMPTS; attempt++) {
            notification.setDeliveryAttemptCount(attempt);
            try {
                provider.send(message);
                notification.setDeliveryStatus("DELIVERED");
                notification.setDeliveryError(null);
                return;
            } catch (Exception e) {
                notification.setDeliveryStatus("FAILED");
                notification.setDeliveryError(truncate(e.getMessage(), 500));
                if (attempt == MAX_NOTIFICATION_ATTEMPTS) {
                    log.warn(
                            "Unable to deliver {} notification for signing request {} after {} attempts",
                            channel,
                            notification.getRequestId(),
                            attempt,
                            e);
                }
            }
        }
    }

    private void auditNotificationDelivery(
            ESignatureWorkflow workflow, String recipientId, ESignatureNotification notification) {
        boolean delivered = "DELIVERED".equals(notification.getDeliveryStatus());
        Map<String, String> details = new LinkedHashMap<>();
        details.put("channel", defaultIfBlank(notification.getDeliveryChannel(), "email"));
        details.put("eventType", defaultIfBlank(notification.getEventType(), "notification"));
        details.put("status", defaultIfBlank(notification.getDeliveryStatus(), "UNKNOWN"));
        details.put("attemptCount", String.valueOf(notification.getDeliveryAttemptCount()));
        if (StringUtils.hasText(notification.getDeliveryError())) {
            details.put("error", notification.getDeliveryError());
        }
        addAudit(
                workflow,
                delivered
                        ? AuditEventType.NOTIFICATION_DELIVERED
                        : AuditEventType.NOTIFICATION_DELIVERY_FAILED,
                recipientId,
                ActorContext.system(),
                delivered ? "Signing notification delivered" : "Signing notification failed",
                details);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sendScheduledReminders() {
        try {
            ESignatureReminderRequest request = new ESignatureReminderRequest();
            request.setOnlyDue(true);
            sendDueReminders(request, ActorContext.system());
        } catch (IOException | RuntimeException e) {
            log.warn("Unable to process scheduled e-signature reminders", e);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private boolean tokenMatches(SigningRecipient recipient, String candidate) {
        if (StringUtils.hasText(recipient.getSigningTokenHash())) {
            byte[] expected = recipient.getSigningTokenHash().getBytes(StandardCharsets.US_ASCII);
            byte[] actual = hashToken(candidate).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, actual);
        }
        if (!StringUtils.hasText(recipient.getSigningToken())) {
            return false;
        }
        return MessageDigest.isEqual(
                recipient.getSigningToken().getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private ESignatureActionResponse actionResponse(
            ESignatureWorkflow workflow, List<ESignatureNotification> notifications) {
        ESignatureActionResponse response = new ESignatureActionResponse();
        response.setRequest(toView(workflow));
        response.setNotifications(new ArrayList<>(notifications));
        return response;
    }

    private ESignatureRequestView toView(ESignatureWorkflow workflow) {
        ESignatureRequestView view = new ESignatureRequestView();
        view.setModelVersion(workflow.getModelVersion());
        view.setId(workflow.getId());
        view.setTitle(workflow.getTitle());
        view.setMessage(workflow.getMessage());
        view.setRequesterName(workflow.getRequesterName());
        view.setRequesterEmail(workflow.getRequesterEmail());
        view.setStatus(workflow.getStatus());
        view.setOriginalFilename(workflow.getOriginalFilename());
        view.setDocumentRevision(workflow.getDocumentRevision());
        view.setCreatedAt(workflow.getCreatedAt());
        view.setUpdatedAt(workflow.getUpdatedAt());
        view.setSentAt(workflow.getSentAt());
        view.setCompletedAt(workflow.getCompletedAt());
        view.setCancelledAt(workflow.getCancelledAt());
        view.setArchivedAt(workflow.getArchivedAt());
        view.setDeclinedAt(workflow.getDeclinedAt());
        view.setExpiresAt(workflow.getExpiresAt());
        view.setRetentionUntil(workflow.getRetentionUntil());
        view.setSigningOrder(workflow.isSigningOrder());
        view.setRemindersEnabled(workflow.isRemindersEnabled());
        view.setReminderIntervalHours(workflow.getReminderIntervalHours());
        view.setCallbackUrl(workflow.getCallbackUrl());
        view.setDownloadUrl("/api/v1/security/e-sign/requests/" + workflow.getId() + "/download");
        view.setAuditEventCount(workflow.getAuditTrail().size());
        view.setRecipients(workflow.getRecipients().stream().map(this::toRecipientView).toList());
        view.setFields(workflow.getFields().stream().map(SigningField::new).toList());
        return view;
    }

    private ESignatureRecipientView toRecipientView(SigningRecipient recipient) {
        ESignatureRecipientView view = new ESignatureRecipientView();
        view.setId(recipient.getId());
        view.setName(recipient.getName());
        view.setEmail(recipient.getEmail());
        view.setPhoneNumber(recipient.getPhoneNumber());
        view.setDeliveryChannel(recipient.getDeliveryChannel());
        view.setRole(recipient.getRole());
        view.setSigningOrder(recipient.getSigningOrder());
        Authentication authentication = recipient.getAuthentication();
        view.setAuthenticationMethod(
                authentication == null ? Method.EMAIL_LINK : authentication.getMethod());
        view.setAuthenticationConfigured(authentication == null || authentication.isConfigured());
        view.setStatus(recipient.getStatus());
        view.setSentAt(recipient.getSentAt());
        view.setViewedAt(recipient.getViewedAt());
        view.setSignedAt(recipient.getSignedAt());
        view.setDeclinedAt(recipient.getDeclinedAt());
        view.setLastReminderAt(recipient.getLastReminderAt());
        view.setReminderCount(recipient.getReminderCount());
        view.setSignerName(recipient.getSignerName());
        view.setSignatureType(recipient.getSignatureType());
        view.setDeclineReason(recipient.getDeclineReason());
        view.setMetadata(copyStringMap(recipient.getMetadata()));
        return view;
    }

    private ESignatureAuditEventView toAuditView(AuditEvent event) {
        ESignatureAuditEventView view = new ESignatureAuditEventView();
        view.setId(event.getId());
        view.setRequestId(event.getRequestId());
        view.setRecipientId(event.getRecipientId());
        view.setType(event.getType());
        view.setActorName(event.getActorName());
        view.setActorEmail(event.getActorEmail());
        view.setIpAddress(event.getIpAddress());
        view.setUserAgent(event.getUserAgent());
        view.setMessage(event.getMessage());
        view.setTimestamp(event.getTimestamp());
        view.setDetails(copyStringMap(event.getDetails()));
        return view;
    }

    private void addAudit(
            ESignatureWorkflow workflow,
            AuditEventType type,
            String recipientId,
            ActorContext actor,
            String message,
            Map<String, String> details) {
        AuditEvent event = new AuditEvent();
        event.setId(UUID.randomUUID().toString());
        event.setRequestId(workflow.getId());
        event.setRecipientId(recipientId);
        event.setType(type);
        event.setActorName(actor == null ? null : actor.name());
        event.setActorEmail(actor == null ? null : actor.email());
        event.setIpAddress(actor == null ? null : actor.ipAddress());
        event.setUserAgent(actor == null ? null : actor.userAgent());
        event.setMessage(message);
        event.setTimestamp(Instant.now());
        event.setDetails(copyStringMap(details));
        workflow.getAuditTrail().add(event);
        enqueueWebhook(workflow, event);
    }

    private void enqueueWebhook(ESignatureWorkflow workflow, AuditEvent event) {
        if (!StringUtils.hasText(workflow.getCallbackUrl())) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", event.getId());
            payload.put("type", event.getType().name());
            payload.put("requestId", workflow.getId());
            payload.put("requestStatus", workflow.getStatus().name());
            payload.put("recipientId", event.getRecipientId());
            payload.put("timestamp", event.getTimestamp());
            payload.put("details", copyStringMap(event.getDetails()));

            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setId(UUID.randomUUID().toString());
            delivery.setRequestId(workflow.getId());
            delivery.setEventId(event.getId());
            delivery.setEventType(event.getType());
            delivery.setPayload(objectMapper.writeValueAsString(payload));
            delivery.setCreatedAt(Instant.now());
            delivery.setNextAttemptAt(Instant.now());
            workflow.getWebhookDeliveries().add(delivery);
        } catch (RuntimeException e) {
            log.warn("Unable to enqueue e-signature webhook for request {}", workflow.getId(), e);
        }
    }

    private boolean isWebhookDue(WebhookDelivery delivery, Instant now) {
        return (delivery.getStatus() == WebhookDeliveryStatus.PENDING
                        || delivery.getStatus() == WebhookDeliveryStatus.RETRYING)
                && delivery.getAttemptCount() < MAX_WEBHOOK_ATTEMPTS
                && (delivery.getNextAttemptAt() == null
                        || !delivery.getNextAttemptAt().isAfter(now));
    }

    private void attemptWebhook(
            ESignatureWorkflow workflow, WebhookDelivery delivery, Instant attemptedAt) {
        ESignatureWebhookService.DeliveryResult result =
                webhookService.deliver(
                        workflow.getCallbackUrl(), delivery.getEventId(), delivery.getPayload());
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(attemptedAt);
        delivery.setLastStatusCode(result.statusCode());
        if (result.delivered()) {
            delivery.setStatus(WebhookDeliveryStatus.DELIVERED);
            delivery.setDeliveredAt(attemptedAt);
            delivery.setNextAttemptAt(null);
            delivery.setLastError(null);
            return;
        }
        delivery.setLastError(truncate(result.error(), 500));
        if (delivery.getAttemptCount() >= MAX_WEBHOOK_ATTEMPTS) {
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            delivery.setNextAttemptAt(null);
            return;
        }
        delivery.setStatus(WebhookDeliveryStatus.RETRYING);
        delivery.setNextAttemptAt(attemptedAt.plus(webhookRetryDelay(delivery.getAttemptCount())));
    }

    private Duration webhookRetryDelay(int attemptCount) {
        long[] minutes = {1, 5, 30, 120, 720, 1440, 2880};
        return Duration.ofMinutes(minutes[Math.min(attemptCount - 1, minutes.length - 1)]);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private List<SigningField> prepareSigningFields(
            List<SigningField> requestedFields, List<SigningRecipient> recipients) {
        if (requestedFields == null || requestedFields.isEmpty()) {
            return new ArrayList<>();
        }
        List<SigningRecipient> assignableRecipients =
                recipients.stream()
                        .filter(recipient -> recipient.getRole() != SigningRecipient.Role.CC)
                        .toList();
        String soleRecipientId =
                assignableRecipients.size() == 1 ? assignableRecipients.get(0).getId() : null;

        List<SigningField> fields = new ArrayList<>();
        for (int index = 0; index < requestedFields.size(); index++) {
            SigningField requestedField = requestedFields.get(index);
            if (requestedField == null) {
                fields.add(null);
                continue;
            }
            SigningField field = new SigningField(requestedField);
            field.setId(requestedOrGeneratedId(field.getId(), "field"));
            field.setName(defaultIfBlank(field.getName(), "field-" + (index + 1)));
            field.setLabel(defaultIfBlank(field.getLabel(), field.getName()));
            if (StringUtils.hasText(field.getRecipientId())) {
                field.setRecipientId(field.getRecipientId().trim());
            } else if (soleRecipientId != null) {
                field.setRecipientId(soleRecipientId);
            }
            fields.add(field);
        }
        return fields;
    }

    private void validateSigningModel(
            List<SigningRecipient> recipients, List<SigningField> fields) {
        List<ValidationIssue> issues = SigningModelValidator.validate(recipients, fields);
        if (issues.isEmpty()) {
            return;
        }
        String details =
                String.join(
                        "; ",
                        issues.stream()
                                .map(issue -> issue.path() + ": " + issue.message())
                                .toList());
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, details);
    }

    private String requestedOrGeneratedId(String requestedId, String modelName) {
        if (!StringUtils.hasText(requestedId)) {
            return UUID.randomUUID().toString();
        }
        String normalized = requestedId.trim();
        if (!SAFE_ID_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid " + modelName + " id");
        }
        return normalized;
    }

    private void validateCreateRequest(ESignatureCreateRequest request) {
        if (request.getRecipients() == null || request.getRecipients().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "At least one recipient is required");
        }
        if (request.getExpiresAt() != null && !request.getExpiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Expiry must be in the future");
        }
        if (request.getReminderIntervalHours() != null && request.getReminderIntervalHours() < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Reminder interval must be at least one hour");
        }
        for (ESignatureRecipientRequest recipient : request.getRecipients()) {
            if (recipient == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Recipient must not be null");
            }
            if (!StringUtils.hasText(recipient.getName())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Recipient name is required");
            }
            if (!StringUtils.hasText(recipient.getEmail())
                    || !RegexPatternUtils.getInstance()
                            .getEmailValidationPattern()
                            .matcher(recipient.getEmail().trim())
                            .matches()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Recipient email is invalid");
            }
            DeliveryChannel deliveryChannel =
                    recipient.getDeliveryChannel() == null
                            ? DeliveryChannel.EMAIL
                            : recipient.getDeliveryChannel();
            if (deliveryChannel == DeliveryChannel.SMS
                    && (!StringUtils.hasText(recipient.getPhoneNumber())
                            || !recipient
                                    .getPhoneNumber()
                                    .trim()
                                    .matches("^\\+[1-9][0-9]{7,14}$"))) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "SMS recipient phone number must use E.164 format");
            }
            Method authenticationMethod =
                    recipient.getAuthenticationMethod() == null
                            ? Method.EMAIL_LINK
                            : recipient.getAuthenticationMethod();
            if (authenticationMethod == Method.ACCESS_CODE
                    && (!StringUtils.hasText(recipient.getAccessCode())
                            || recipient.getAccessCode().length() < 6
                            || recipient.getAccessCode().length() > 128)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Recipient access code must contain between 6 and 128 characters");
            }
        }
    }

    private void validatePdfFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A PDF file is required");
        }
        String filename = sanitizeOriginalFilename(file.getOriginalFilename());
        if (!PDF_EXTENSION_PATTERN.matcher(filename).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be a PDF");
        }
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "PDF exceeds the 100 GiB workflow limit");
        }
        byte[] header = new byte[5];
        try (InputStream inputStream = file.getInputStream()) {
            if (inputStream.readNBytes(header, 0, header.length) != header.length
                    || header[0] != '%'
                    || header[1] != 'P'
                    || header[2] != 'D'
                    || header[3] != 'F'
                    || header[4] != '-') {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "File content must be a PDF");
            }
        }
    }

    private String sanitizeOriginalFilename(String filename) {
        String simple = Filenames.toSimpleFileName(filename);
        if (!StringUtils.hasText(simple)) {
            return "document.pdf";
        }
        return simple;
    }

    private WorkflowStatus parseWorkflowStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return WorkflowStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown workflow status");
        }
    }

    private AuditEventType parseAuditEventType(String type) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        try {
            return AuditEventType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown audit event type");
        }
    }

    private void ensureWorkflowCanContinue(ESignatureWorkflow workflow) {
        if (!isActiveWorkflow(workflow)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "E-signature request is not active: " + workflow.getStatus());
        }
    }

    private void ensureSignerFieldsAssigned(ESignatureWorkflow workflow) {
        for (SigningRecipient recipient : workflow.getRecipients()) {
            if (recipient.getRole() != SigningRecipient.Role.SIGNER) {
                continue;
            }
            boolean hasField =
                    workflow.getFields().stream()
                            .anyMatch(
                                    field ->
                                            field != null
                                                    && Objects.equals(
                                                            field.getRecipientId(),
                                                            recipient.getId()));
            if (!hasField) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Every signer must have at least one assigned field");
            }
        }
    }

    private boolean isActiveWorkflow(ESignatureWorkflow workflow) {
        return workflow.getStatus() == WorkflowStatus.DRAFT
                || workflow.getStatus() == WorkflowStatus.SENT
                || workflow.getStatus() == WorkflowStatus.IN_PROGRESS;
    }

    private void ensureRecipientCanAct(SigningRecipient recipient) {
        if (recipient.getRole() == SigningRecipient.Role.CC) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Copy recipients cannot sign or decline");
        }
        if (recipient.getStatus() == Status.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Recipient has not been sent a signing request");
        }
        if (recipient.getStatus() == Status.SIGNED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recipient has already signed");
        }
        if (recipient.getStatus() == Status.DECLINED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recipient has declined");
        }
        if (recipient.getStatus() == Status.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.GONE, "Recipient signing token expired");
        }
    }

    private void verifyRecipientAuthentication(
            ESignatureWorkflow workflow, SigningRecipient recipient, String accessCode)
            throws IOException {
        Authentication authentication = recipient.getAuthentication();
        if (authentication == null || authentication.getMethod() == Method.EMAIL_LINK) {
            return;
        }
        Instant now = Instant.now();
        if (authentication.getLockedUntil() != null
                && authentication.getLockedUntil().isAfter(now)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Recipient authentication is temporarily locked");
        }
        if (authentication.getMethod() == Method.ACCESS_CODE
                && SigningAccessCodeHasher.matches(
                        accessCode, authentication.getAccessCodeHash())) {
            if (authentication.getFailedAttempts() > 0 || authentication.getLockedUntil() != null) {
                authentication.setFailedAttempts(0);
                authentication.setLockedUntil(null);
                saveWorkflow(workflow);
            }
            return;
        }
        authentication.setFailedAttempts(authentication.getFailedAttempts() + 1);
        AuditEventType eventType = AuditEventType.RECIPIENT_AUTHENTICATION_FAILED;
        if (authentication.getFailedAttempts() >= MAX_AUTHENTICATION_ATTEMPTS) {
            authentication.setLockedUntil(now.plus(AUTHENTICATION_LOCK_DURATION));
            eventType = AuditEventType.RECIPIENT_AUTHENTICATION_LOCKED;
        }
        addAudit(
                workflow,
                eventType,
                recipient.getId(),
                ActorContext.system(),
                "Recipient authentication attempt rejected",
                Map.of("attemptCount", String.valueOf(authentication.getFailedAttempts())));
        saveWorkflow(workflow);
        throw new ResponseStatusException(
                eventType == AuditEventType.RECIPIENT_AUTHENTICATION_LOCKED
                        ? HttpStatus.TOO_MANY_REQUESTS
                        : HttpStatus.UNAUTHORIZED,
                eventType == AuditEventType.RECIPIENT_AUTHENTICATION_LOCKED
                        ? "Recipient authentication is temporarily locked"
                        : "Recipient authentication failed");
    }

    private boolean isTerminalRecipient(SigningRecipient recipient) {
        return recipient.getStatus() == Status.SIGNED
                || recipient.getStatus() == Status.DECLINED
                || recipient.getStatus() == Status.EXPIRED;
    }

    private boolean allRecipientsSigned(ESignatureWorkflow workflow) {
        return workflow.getRecipients().stream()
                .filter(recipient -> recipient.getRole() != SigningRecipient.Role.CC)
                .allMatch(recipient -> recipient.getStatus() == Status.SIGNED);
    }

    private boolean isRecipientInActiveOrder(
            ESignatureWorkflow workflow, SigningRecipient recipient) {
        int activeOrder =
                workflow.getRecipients().stream()
                        .filter(candidate -> candidate.getRole() != SigningRecipient.Role.CC)
                        .filter(candidate -> !isTerminalRecipient(candidate))
                        .mapToInt(SigningRecipient::getSigningOrder)
                        .min()
                        .orElse(recipient.getSigningOrder());
        return recipient.getSigningOrder() == activeOrder;
    }

    private Map<String, String> copyStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> {
                    if (key != null && value != null) {
                        copy.put(key, value);
                    }
                });
        return copy;
    }

    private Map<String, String> mergeStringMaps(
            Map<String, String> existing, Map<String, String> updates) {
        Map<String, String> merged = copyStringMap(existing);
        merged.putAll(copyStringMap(updates));
        return merged;
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private void validateSafeId(String requestId) {
        if (!StringUtils.hasText(requestId) || !SAFE_ID_PATTERN.matcher(requestId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request id");
        }
    }

    private Path requestPath(String requestId) {
        validateSafeId(requestId);
        return requestsPath.resolve(requestId).normalize();
    }

    private Path metadataPath(String requestId) {
        return requestPath(requestId).resolve(METADATA_FILE);
    }

    private void verifyWithin(Path parent, Path child) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedParent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid storage path");
        }
    }

    private ActorContext actorOrSystem(ActorContext actor) {
        return actor == null ? ActorContext.system() : actor;
    }

    private boolean isOwnedBy(ESignatureWorkflow workflow, ActorContext actor) {
        return !StringUtils.hasText(workflow.getOwnerId())
                || actor == null
                || !StringUtils.hasText(actor.principalId())
                || workflow.getOwnerId().equals(actor.principalId());
    }

    private void ensureOwnedBy(ESignatureWorkflow workflow, ActorContext actor) {
        if (!isOwnedBy(workflow, actor)) {
            // Avoid revealing whether another user's request exists.
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "E-signature request not found");
        }
    }

    public record ActorContext(
            String name, String email, String ipAddress, String userAgent, String principalId) {
        public ActorContext(String name, String email, String ipAddress, String userAgent) {
            this(name, email, ipAddress, userAgent, null);
        }

        public static ActorContext system() {
            return new ActorContext("system", null, null, null, null);
        }

        public ActorContext withFallback(String fallbackName, String fallbackEmail) {
            return new ActorContext(
                    StringUtils.hasText(name) ? name : fallbackName,
                    StringUtils.hasText(email) ? email : fallbackEmail,
                    ipAddress,
                    userAgent,
                    principalId);
        }

        public ActorContext withNetworkFallback(
                String fallbackIpAddress, String fallbackUserAgent) {
            return new ActorContext(
                    name,
                    email,
                    StringUtils.hasText(ipAddress) ? ipAddress : fallbackIpAddress,
                    StringUtils.hasText(userAgent) ? userAgent : fallbackUserAgent,
                    principalId);
        }
    }

    private record TokenResolution(ESignatureWorkflow workflow, SigningRecipient recipient) {}

    @Getter
    public static class DocumentDownload {
        private final Path path;
        private final String filename;

        public DocumentDownload(Path path, String filename) {
            this.path = path;
            this.filename = filename;
        }
    }
}
