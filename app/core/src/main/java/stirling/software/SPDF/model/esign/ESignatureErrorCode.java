package stirling.software.SPDF.model.esign;

/**
 * Stable error codes returned on the {@code code} property of Problem Details responses for
 * e-signature workflow conflicts. Clients branch on these instead of parsing messages.
 */
public final class ESignatureErrorCode {

    /** The caller's {@code expectedWorkflowRevision} no longer matches the stored workflow. */
    public static final String WORKFLOW_REVISION_STALE = "WORKFLOW_REVISION_STALE";

    /** An {@code idempotencyKey} was reused for a different action or payload. */
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";

    /** The workflow is not in a state that allows the requested transition. */
    public static final String WORKFLOW_STATE_INVALID = "WORKFLOW_STATE_INVALID";

    /** The recipient is not in a state that allows the requested transition. */
    public static final String RECIPIENT_STATE_INVALID = "RECIPIENT_STATE_INVALID";

    /** Another recipient must act first because signing order is enforced. */
    public static final String SIGNING_ORDER_BLOCKED = "SIGNING_ORDER_BLOCKED";

    /** The step-up code was consumed but the signature could not be written. */
    public static final String SIGNATURE_APPLY_FAILED = "SIGNATURE_APPLY_FAILED";

    private ESignatureErrorCode() {}
}
