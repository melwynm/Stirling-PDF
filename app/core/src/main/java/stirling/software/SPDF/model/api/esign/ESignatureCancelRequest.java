package stirling.software.SPDF.model.api.esign;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureCancelRequest {
    private String reason;

    /** Optional: reject the action when the workflow revision has moved on (409). */
    private Long expectedWorkflowRevision;

    /** Optional: replay the stored result when the same action is submitted again. */
    private String idempotencyKey;
}
