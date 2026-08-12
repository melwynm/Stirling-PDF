package stirling.software.SPDF.model.api.esign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

import stirling.software.SPDF.model.esign.ESignatureWorkflow.WorkflowStatus;
import stirling.software.SPDF.model.signing.SigningField;

@Data
@NoArgsConstructor
public class ESignatureRequestView {
    private int modelVersion;
    private String id;
    private String title;
    private String message;
    private String requesterName;
    private String requesterEmail;
    private WorkflowStatus status;
    private String originalFilename;
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
    private boolean signingOrder;
    private boolean remindersEnabled;
    private int reminderIntervalHours;
    private String callbackUrl;
    private String downloadUrl;
    private int auditEventCount;
    private List<ESignatureRecipientView> recipients = new ArrayList<>();
    private List<SigningField> fields = new ArrayList<>();
}
