package stirling.software.SPDF.service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import stirling.software.SPDF.exception.CodedResponseStatusException;
import stirling.software.SPDF.model.esign.ESignatureWorkflow;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WorkflowStatus;
import stirling.software.SPDF.model.signing.SigningRecipient;
import stirling.software.SPDF.model.signing.SigningRecipient.Status;

/**
 * The single source of truth for e-signature workflow and recipient state transitions.
 *
 * <p>Every mutation in {@link ESignatureWorkflowService} moves a workflow or recipient through
 * {@link #transition} so an illegal move is rejected with {@code 409} and a stable error code.
 * Staying in the same state is always allowed (for example sending additional recipients of a
 * workflow that is already {@code SENT}).
 */
public final class ESignatureWorkflowTransitions {

    public static final String WORKFLOW_STATE_INVALID = "WORKFLOW_STATE_INVALID";
    public static final String RECIPIENT_STATE_INVALID = "RECIPIENT_STATE_INVALID";

    private static final Map<WorkflowStatus, Set<WorkflowStatus>> WORKFLOW =
            new EnumMap<>(WorkflowStatus.class);
    private static final Map<Status, Set<Status>> RECIPIENT = new EnumMap<>(Status.class);

    static {
        WORKFLOW.put(
                WorkflowStatus.DRAFT,
                EnumSet.of(WorkflowStatus.SENT, WorkflowStatus.CANCELLED, WorkflowStatus.EXPIRED));
        WORKFLOW.put(
                WorkflowStatus.SENT,
                EnumSet.of(
                        WorkflowStatus.IN_PROGRESS,
                        WorkflowStatus.COMPLETED,
                        WorkflowStatus.DECLINED,
                        WorkflowStatus.CANCELLED,
                        WorkflowStatus.EXPIRED));
        WORKFLOW.put(
                WorkflowStatus.IN_PROGRESS,
                EnumSet.of(
                        WorkflowStatus.COMPLETED,
                        WorkflowStatus.DECLINED,
                        WorkflowStatus.CANCELLED,
                        WorkflowStatus.EXPIRED));
        WORKFLOW.put(WorkflowStatus.COMPLETED, EnumSet.of(WorkflowStatus.ARCHIVED));
        WORKFLOW.put(WorkflowStatus.DECLINED, EnumSet.of(WorkflowStatus.ARCHIVED));
        WORKFLOW.put(WorkflowStatus.CANCELLED, EnumSet.of(WorkflowStatus.ARCHIVED));
        WORKFLOW.put(WorkflowStatus.EXPIRED, EnumSet.of(WorkflowStatus.ARCHIVED));
        WORKFLOW.put(WorkflowStatus.ARCHIVED, EnumSet.noneOf(WorkflowStatus.class));

        RECIPIENT.put(Status.PENDING, EnumSet.of(Status.SENT, Status.EXPIRED));
        RECIPIENT.put(
                Status.SENT,
                EnumSet.of(Status.VIEWED, Status.SIGNED, Status.DECLINED, Status.EXPIRED));
        RECIPIENT.put(Status.VIEWED, EnumSet.of(Status.SIGNED, Status.DECLINED, Status.EXPIRED));
        RECIPIENT.put(Status.SIGNED, EnumSet.noneOf(Status.class));
        RECIPIENT.put(Status.DECLINED, EnumSet.noneOf(Status.class));
        RECIPIENT.put(Status.EXPIRED, EnumSet.noneOf(Status.class));
    }

    private ESignatureWorkflowTransitions() {}

    /** Workflow states that accept recipient actions and sender changes. */
    public static boolean isActive(WorkflowStatus status) {
        return status == WorkflowStatus.DRAFT
                || status == WorkflowStatus.SENT
                || status == WorkflowStatus.IN_PROGRESS;
    }

    /** Recipient states that accept no further action. */
    public static boolean isTerminal(Status status) {
        return RECIPIENT.get(status).isEmpty();
    }

    public static Set<WorkflowStatus> allowedFrom(WorkflowStatus status) {
        return Set.copyOf(WORKFLOW.get(status));
    }

    public static Set<Status> allowedFrom(Status status) {
        return Set.copyOf(RECIPIENT.get(status));
    }

    public static boolean canTransition(WorkflowStatus from, WorkflowStatus to) {
        return from == to || WORKFLOW.get(from).contains(to);
    }

    public static boolean canTransition(Status from, Status to) {
        return from == to || RECIPIENT.get(from).contains(to);
    }

    /** Moves the workflow to {@code target} or throws {@code 409 WORKFLOW_STATE_INVALID}. */
    public static void transition(ESignatureWorkflow workflow, WorkflowStatus target) {
        WorkflowStatus current = workflow.getStatus();
        if (!canTransition(current, target)) {
            throw new CodedResponseStatusException(
                    HttpStatus.CONFLICT,
                    WORKFLOW_STATE_INVALID,
                    "E-signature request cannot move from " + current + " to " + target,
                    Map.of("currentStatus", current.name(), "requestedStatus", target.name()));
        }
        workflow.setStatus(target);
    }

    /** Moves the recipient to {@code target} or throws {@code 409 RECIPIENT_STATE_INVALID}. */
    public static void transition(SigningRecipient recipient, Status target) {
        Status current = recipient.getStatus();
        if (!canTransition(current, target)) {
            throw new CodedResponseStatusException(
                    HttpStatus.CONFLICT,
                    RECIPIENT_STATE_INVALID,
                    "Recipient cannot move from " + current + " to " + target,
                    Map.of("currentStatus", current.name(), "requestedStatus", target.name()));
        }
        recipient.setStatus(target);
    }
}
