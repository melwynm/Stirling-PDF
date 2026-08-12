package stirling.software.SPDF.model.esign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

import stirling.software.SPDF.model.signing.SigningField;
import stirling.software.SPDF.model.signing.SigningRecipient;

@Data
@NoArgsConstructor
public class ESignatureWorkflow {

    public static final int CURRENT_MODEL_VERSION = 1;

    private int modelVersion = CURRENT_MODEL_VERSION;
    private String id;
    private String ownerId;
    private String title;
    private String message;
    private String requesterName;
    private String requesterEmail;
    private WorkflowStatus status = WorkflowStatus.DRAFT;
    private String originalFilename;
    private String documentFileName = "document.pdf";
    private int documentRevision;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant sentAt;
    private Instant completedAt;
    private Instant cancelledAt;
    private Instant archivedAt;
    private Instant declinedAt;
    private Instant expiresAt;
    private Instant retentionUntil;
    private String publicBaseUrl;
    private String callbackUrl;
    private boolean signingOrder;
    private boolean remindersEnabled = true;
    private int reminderIntervalHours = 48;
    private List<SigningRecipient> recipients = new ArrayList<>();
    private List<SigningField> fields = new ArrayList<>();
    private List<AuditEvent> auditTrail = new ArrayList<>();
    private List<WebhookDelivery> webhookDeliveries = new ArrayList<>();

    public enum WorkflowStatus {
        DRAFT,
        SENT,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED,
        ARCHIVED,
        DECLINED,
        EXPIRED
    }

    public enum AuditEventType {
        REQUEST_CREATED,
        DOCUMENT_UPLOADED,
        DOCUMENT_UPDATED,
        RECIPIENT_ADDED,
        REQUEST_SENT,
        RECIPIENT_SENT,
        RECIPIENT_VIEWED,
        RECIPIENT_AUTHENTICATION_FAILED,
        RECIPIENT_AUTHENTICATION_LOCKED,
        NOTIFICATION_DELIVERED,
        NOTIFICATION_DELIVERY_FAILED,
        RECIPIENT_SIGNED,
        RECIPIENT_DECLINED,
        REMINDER_SENT,
        REQUEST_COMPLETED,
        REQUEST_CANCELLED,
        REQUEST_ARCHIVED,
        REQUEST_EXPIRED
    }

    public enum WebhookDeliveryStatus {
        PENDING,
        RETRYING,
        DELIVERED,
        FAILED
    }

    @Data
    @NoArgsConstructor
    public static class AuditEvent {
        private String id;
        private String requestId;
        private String recipientId;
        private AuditEventType type;
        private String actorName;
        private String actorEmail;
        private String ipAddress;
        private String userAgent;
        private String message;
        private Instant timestamp;
        private String previousHash;
        private String eventHash;
        private Map<String, String> details = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    public static class WebhookDelivery {
        private String id;
        private String requestId;
        private String eventId;
        private AuditEventType eventType;
        private String payload;
        private WebhookDeliveryStatus status = WebhookDeliveryStatus.PENDING;
        private int attemptCount;
        private Instant createdAt;
        private Instant nextAttemptAt;
        private Instant lastAttemptAt;
        private Instant deliveredAt;
        private Integer lastStatusCode;
        private String lastError;
    }
}
