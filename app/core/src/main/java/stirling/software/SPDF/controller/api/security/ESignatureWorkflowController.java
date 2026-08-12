package stirling.software.SPDF.controller.api.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.model.api.esign.ESignatureActionResponse;
import stirling.software.SPDF.model.api.esign.ESignatureAuditEventView;
import stirling.software.SPDF.model.api.esign.ESignatureCancelRequest;
import stirling.software.SPDF.model.api.esign.ESignatureCreateRequest;
import stirling.software.SPDF.model.api.esign.ESignatureDeclineRequest;
import stirling.software.SPDF.model.api.esign.ESignatureDueReminder;
import stirling.software.SPDF.model.api.esign.ESignatureNotification;
import stirling.software.SPDF.model.api.esign.ESignatureRecipientRequest;
import stirling.software.SPDF.model.api.esign.ESignatureReminderRequest;
import stirling.software.SPDF.model.api.esign.ESignatureRequestView;
import stirling.software.SPDF.model.api.esign.ESignatureSendRequest;
import stirling.software.SPDF.model.api.esign.ESignatureSignRequest;
import stirling.software.SPDF.model.api.esign.ESignatureTokenContext;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WebhookDelivery;
import stirling.software.SPDF.service.ESignatureWorkflowService;
import stirling.software.SPDF.service.ESignatureWorkflowService.ActorContext;
import stirling.software.common.annotations.api.SecurityApi;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SecurityApi
@RequiredArgsConstructor
public class ESignatureWorkflowController {

    private static final TypeReference<List<ESignatureRecipientRequest>> RECIPIENT_LIST_TYPE =
            new TypeReference<>() {};

    private final ESignatureWorkflowService workflowService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/e-sign/requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Create an e-signature workflow",
            description =
                    "Stores a PDF and creates a trackable e-signature request with recipients,"
                            + " reminders, signing order, and audit trail. n8n can send a"
                            + " multipart request with a PDF file and either a JSON request part or"
                            + " form fields. Input:PDF Output:JSON Type:SISO")
    public ResponseEntity<ESignatureRequestView> createRequest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "request", required = false) String requestJson,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "requesterName", required = false) String requesterName,
            @RequestParam(value = "requesterEmail", required = false) String requesterEmail,
            @RequestParam(value = "recipients", required = false) String recipientsJson,
            @RequestParam(value = "publicBaseUrl", required = false) String publicBaseUrl,
            @RequestParam(value = "callbackUrl", required = false) String callbackUrl,
            @RequestParam(value = "expiresAt", required = false) String expiresAt,
            @RequestParam(value = "signingOrder", defaultValue = "false") boolean signingOrder,
            @RequestParam(value = "remindersEnabled", defaultValue = "true")
                    boolean remindersEnabled,
            @RequestParam(value = "reminderIntervalHours", required = false)
                    Integer reminderIntervalHours,
            HttpServletRequest servletRequest)
            throws IOException {
        ESignatureCreateRequest request =
                parseCreateRequest(
                        requestJson,
                        title,
                        message,
                        requesterName,
                        requesterEmail,
                        recipientsJson,
                        publicBaseUrl,
                        callbackUrl,
                        expiresAt,
                        signingOrder,
                        remindersEnabled,
                        reminderIntervalHours);
        if (!StringUtils.hasText(request.getPublicBaseUrl())) {
            request.setPublicBaseUrl(baseUrl(servletRequest));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workflowService.createRequest(file, request, actor(servletRequest, request)));
    }

    @GetMapping("/e-sign/requests")
    @Operation(summary = "List e-signature workflows")
    public ResponseEntity<List<ESignatureRequestView>> listRequests(
            @RequestParam(value = "status", required = false) String status,
            HttpServletRequest servletRequest)
            throws IOException {
        return ResponseEntity.ok(
                workflowService.listRequests(status, actor(servletRequest, null, null)));
    }

    @GetMapping("/e-sign/requests/{requestId}")
    @Operation(summary = "Get e-signature workflow status")
    public ResponseEntity<ESignatureRequestView> getRequest(
            @PathVariable String requestId, HttpServletRequest servletRequest) throws IOException {
        return ResponseEntity.ok(
                workflowService.getRequest(requestId, actor(servletRequest, null, null)));
    }

    @PostMapping("/e-sign/requests/{requestId}/send")
    @Operation(
            summary = "Issue signing links for recipients",
            description =
                    "Moves a workflow into sent state and returns per-recipient notification"
                            + " payloads. n8n can use the returned signingUrl/token values to send"
                            + " email, SMS, or Teams messages.")
    public ResponseEntity<ESignatureActionResponse> sendRequest(
            @PathVariable String requestId,
            @RequestBody(required = false) ESignatureSendRequest request,
            HttpServletRequest servletRequest)
            throws IOException {
        if (request == null) {
            request = new ESignatureSendRequest();
        }
        if (!StringUtils.hasText(request.getPublicBaseUrl())) {
            request.setPublicBaseUrl(baseUrl(servletRequest));
        }
        return ResponseEntity.ok(
                workflowService.sendRequest(requestId, request, actor(servletRequest, null, null)));
    }

    @PostMapping("/e-sign/requests/{requestId}/reminders")
    @Operation(summary = "Issue reminders for one e-signature workflow")
    public ResponseEntity<ESignatureActionResponse> sendReminders(
            @PathVariable String requestId,
            @RequestBody(required = false) ESignatureReminderRequest request,
            HttpServletRequest servletRequest)
            throws IOException {
        if (request == null) {
            request = new ESignatureReminderRequest();
        }
        if (!StringUtils.hasText(request.getPublicBaseUrl())) {
            request.setPublicBaseUrl(baseUrl(servletRequest));
        }
        return ResponseEntity.ok(
                workflowService.sendReminders(
                        requestId, request, actor(servletRequest, null, null)));
    }

    @GetMapping("/e-sign/reminders/due")
    @Operation(summary = "List due e-signature reminders")
    public ResponseEntity<List<ESignatureDueReminder>> getDueReminders() throws IOException {
        return ResponseEntity.ok(workflowService.getDueReminders());
    }

    @PostMapping("/e-sign/reminders/due")
    @Operation(
            summary = "Issue all due e-signature reminders",
            description =
                    "Returns notification payloads and records reminder audit events. Intended for"
                            + " n8n cron workflows.")
    public ResponseEntity<List<ESignatureNotification>> sendDueReminders(
            @RequestBody(required = false) ESignatureReminderRequest request,
            HttpServletRequest servletRequest)
            throws IOException {
        if (request == null) {
            request = new ESignatureReminderRequest();
        }
        request.setOnlyDue(true);
        if (!StringUtils.hasText(request.getPublicBaseUrl())) {
            request.setPublicBaseUrl(baseUrl(servletRequest));
        }
        return ResponseEntity.ok(
                workflowService.sendDueReminders(request, actor(servletRequest, null, null)));
    }

    @GetMapping("/e-sign/requests/{requestId}/audit")
    @Operation(summary = "Get e-signature audit trail")
    public ResponseEntity<List<ESignatureAuditEventView>> getAuditTrail(
            @PathVariable String requestId, HttpServletRequest servletRequest) throws IOException {
        return ResponseEntity.ok(
                workflowService.getAuditTrail(requestId, actor(servletRequest, null, null)));
    }

    @GetMapping("/e-sign/events")
    @Operation(summary = "List e-signature audit events for automation polling")
    public ResponseEntity<List<ESignatureAuditEventView>> listEvents(
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "type", required = false) String type,
            HttpServletRequest servletRequest)
            throws IOException {
        return ResponseEntity.ok(
                workflowService.listEvents(
                        parseInstant(since), type, actor(servletRequest, null, null)));
    }

    @PostMapping("/e-sign/webhooks/due")
    @Operation(
            summary = "Deliver pending e-signature webhooks",
            description =
                    "Processes persisted callback events with bounded retries. Intended for a"
                            + " scheduled automation worker.")
    public ResponseEntity<List<WebhookDelivery>> processDueWebhooks(
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            HttpServletRequest servletRequest)
            throws IOException {
        return ResponseEntity.ok(
                workflowService.processDueWebhooks(limit, actor(servletRequest, null, null)));
    }

    @PostMapping("/e-sign/requests/{requestId}/webhooks/retry")
    @Operation(summary = "Retry failed webhooks for an e-signature workflow")
    public ResponseEntity<List<WebhookDelivery>> retryWebhooks(
            @PathVariable String requestId, HttpServletRequest servletRequest) throws IOException {
        return ResponseEntity.ok(
                workflowService.retryWebhooks(requestId, actor(servletRequest, null, null)));
    }

    @GetMapping("/e-sign/requests/{requestId}/download")
    @Operation(summary = "Download the PDF attached to an e-signature workflow")
    public ResponseEntity<InputStreamResource> downloadRequestDocument(
            @PathVariable String requestId, HttpServletRequest servletRequest) throws IOException {
        ActorContext actor = actor(servletRequest, null, null);
        Path documentPath = workflowService.getDocumentPath(requestId, actor);
        return streamPdf(documentPath, workflowService.getOriginalFilename(requestId, actor));
    }

    @PostMapping("/e-sign/requests/{requestId}/cancel")
    @Operation(summary = "Cancel an e-signature workflow")
    public ResponseEntity<ESignatureRequestView> cancel(
            @PathVariable String requestId,
            @RequestBody(required = false) ESignatureCancelRequest request,
            HttpServletRequest servletRequest)
            throws IOException {
        return ResponseEntity.ok(
                workflowService.cancel(requestId, request, actor(servletRequest, null, null)));
    }

    @PostMapping("/e-sign/requests/{requestId}/archive")
    @Operation(summary = "Archive a terminal e-signature workflow")
    public ResponseEntity<ESignatureRequestView> archive(
            @PathVariable String requestId, HttpServletRequest servletRequest) throws IOException {
        return ResponseEntity.ok(
                workflowService.archive(requestId, actor(servletRequest, null, null)));
    }

    @DeleteMapping("/e-sign/requests/{requestId}")
    @Operation(summary = "Permanently delete an archived e-signature workflow")
    public ResponseEntity<Void> delete(
            @PathVariable String requestId, HttpServletRequest servletRequest) throws IOException {
        workflowService.delete(requestId, actor(servletRequest, null, null));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/e-sign/recipients/{token}")
    @Operation(summary = "Get public signing context for a recipient token")
    public ResponseEntity<ESignatureTokenContext> getTokenContext(@PathVariable String token)
            throws IOException {
        return ResponseEntity.ok(workflowService.getTokenContext(token));
    }

    @GetMapping("/e-sign/recipients/{token}/download")
    @Operation(summary = "Download the PDF for a recipient signing token")
    public ResponseEntity<InputStreamResource> downloadTokenDocument(
            @PathVariable String token,
            @RequestHeader(value = "X-Signing-Access-Code", required = false) String accessCode)
            throws IOException {
        ESignatureWorkflowService.DocumentDownload download =
                workflowService.getDocumentForToken(token, accessCode);
        return streamPdf(download.getPath(), download.getFilename());
    }

    @PostMapping("/e-sign/recipients/{token}/viewed")
    @Operation(summary = "Mark an e-signature recipient as viewed")
    public ResponseEntity<ESignatureActionResponse> markViewed(
            @PathVariable String token,
            @RequestHeader(value = "X-Signing-Access-Code", required = false) String accessCode,
            HttpServletRequest servletRequest)
            throws IOException {
        return ResponseEntity.ok(
                workflowService.markViewed(token, accessCode, actor(servletRequest, null, null)));
    }

    @PostMapping("/e-sign/recipients/{token}/sign")
    @Operation(summary = "Complete a recipient signature step")
    public ResponseEntity<ESignatureActionResponse> sign(
            @PathVariable String token,
            @RequestBody ESignatureSignRequest request,
            HttpServletRequest servletRequest)
            throws IOException {
        return ResponseEntity.ok(
                workflowService.sign(token, request, actor(servletRequest, null, null)));
    }

    @PostMapping("/e-sign/recipients/{token}/decline")
    @Operation(summary = "Decline a recipient signature request")
    public ResponseEntity<ESignatureActionResponse> decline(
            @PathVariable String token,
            @RequestBody(required = false) ESignatureDeclineRequest request,
            HttpServletRequest servletRequest)
            throws IOException {
        return ResponseEntity.ok(
                workflowService.decline(token, request, actor(servletRequest, null, null)));
    }

    private ESignatureCreateRequest parseCreateRequest(
            String requestJson,
            String title,
            String message,
            String requesterName,
            String requesterEmail,
            String recipientsJson,
            String publicBaseUrl,
            String callbackUrl,
            String expiresAt,
            boolean signingOrder,
            boolean remindersEnabled,
            Integer reminderIntervalHours)
            throws IOException {
        if (StringUtils.hasText(requestJson)) {
            return objectMapper.readValue(requestJson, ESignatureCreateRequest.class);
        }
        ESignatureCreateRequest request = new ESignatureCreateRequest();
        request.setTitle(title);
        request.setMessage(message);
        request.setRequesterName(requesterName);
        request.setRequesterEmail(requesterEmail);
        request.setPublicBaseUrl(publicBaseUrl);
        request.setCallbackUrl(callbackUrl);
        request.setSigningOrder(signingOrder);
        request.setRemindersEnabled(remindersEnabled);
        request.setReminderIntervalHours(reminderIntervalHours);
        request.setExpiresAt(parseInstant(expiresAt));
        if (StringUtils.hasText(recipientsJson)) {
            request.setRecipients(objectMapper.readValue(recipientsJson, RECIPIENT_LIST_TYPE));
        }
        return request;
    }

    private Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Timestamp must be ISO-8601 UTC, for example 2026-07-04T12:00:00Z");
        }
    }

    private ResponseEntity<InputStreamResource> streamPdf(Path documentPath, String filename)
            throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(Files.size(documentPath))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(new InputStreamResource(Files.newInputStream(documentPath)));
    }

    private ActorContext actor(HttpServletRequest request, ESignatureCreateRequest createRequest) {
        return actor(
                request,
                createRequest == null ? null : createRequest.getRequesterName(),
                createRequest == null ? null : createRequest.getRequesterEmail());
    }

    private ActorContext actor(HttpServletRequest request, String actorName, String actorEmail) {
        String principalId =
                request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName();
        return new ActorContext(
                actorName,
                actorEmail,
                clientIp(request),
                request.getHeader("User-Agent"),
                principalId);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String baseUrl(HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String scheme = StringUtils.hasText(forwardedProto) ? forwardedProto : request.getScheme();
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String host =
                StringUtils.hasText(forwardedHost) ? forwardedHost : request.getHeader("Host");
        if (StringUtils.hasText(host)) {
            return scheme + "://" + host;
        }
        int port = request.getServerPort();
        boolean defaultPort =
                ("http".equalsIgnoreCase(scheme) && port == 80)
                        || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }
}
