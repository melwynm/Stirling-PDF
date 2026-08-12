package stirling.software.SPDF.model.api.esign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureEvidenceView {
    private String requestId;
    private String title;
    private String status;
    private Instant generatedAt;
    private boolean auditIntegrityValid;
    private String auditRootHash;
    private String documentSha256;
    private int documentRevision;
    private List<ESignatureRecipientView> recipients = new ArrayList<>();
    private List<ESignatureAuditEventView> auditTrail = new ArrayList<>();
}
