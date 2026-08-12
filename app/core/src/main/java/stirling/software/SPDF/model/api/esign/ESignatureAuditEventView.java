package stirling.software.SPDF.model.api.esign;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

import stirling.software.SPDF.model.esign.ESignatureWorkflow.AuditEventType;

@Data
@NoArgsConstructor
public class ESignatureAuditEventView {
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
