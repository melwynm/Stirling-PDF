package stirling.software.SPDF.model.api.esign;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureDeclineRequest {
    private String reason;
    private String accessCode;
    private String ipAddress;
    private String userAgent;

    /** Optional: reject the action when the workflow revision has moved on (409). */
    private Long expectedWorkflowRevision;

    /** Optional: replay the stored result when the same action is submitted again. */
    private String idempotencyKey;
}
