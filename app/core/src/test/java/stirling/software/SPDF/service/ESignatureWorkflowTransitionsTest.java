package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import stirling.software.SPDF.exception.CodedResponseStatusException;
import stirling.software.SPDF.model.esign.ESignatureErrorCode;
import stirling.software.SPDF.model.esign.ESignatureWorkflow;
import stirling.software.SPDF.model.esign.ESignatureWorkflow.WorkflowStatus;
import stirling.software.SPDF.model.signing.SigningRecipient;
import stirling.software.SPDF.model.signing.SigningRecipient.Status;

/** Enumerates every allowed workflow and recipient transition; anything else is a 409. */
class ESignatureWorkflowTransitionsTest {

    private static final Map<WorkflowStatus, Set<WorkflowStatus>> WORKFLOW =
            Map.of(
                    WorkflowStatus.DRAFT,
                    EnumSet.of(
                            WorkflowStatus.SENT, WorkflowStatus.CANCELLED, WorkflowStatus.EXPIRED),
                    WorkflowStatus.SENT,
                    EnumSet.of(
                            WorkflowStatus.IN_PROGRESS,
                            WorkflowStatus.COMPLETED,
                            WorkflowStatus.DECLINED,
                            WorkflowStatus.CANCELLED,
                            WorkflowStatus.EXPIRED),
                    WorkflowStatus.IN_PROGRESS,
                    EnumSet.of(
                            WorkflowStatus.COMPLETED,
                            WorkflowStatus.DECLINED,
                            WorkflowStatus.CANCELLED,
                            WorkflowStatus.EXPIRED),
                    WorkflowStatus.COMPLETED,
                    EnumSet.of(WorkflowStatus.ARCHIVED),
                    WorkflowStatus.DECLINED,
                    EnumSet.of(WorkflowStatus.ARCHIVED),
                    WorkflowStatus.CANCELLED,
                    EnumSet.of(WorkflowStatus.ARCHIVED),
                    WorkflowStatus.EXPIRED,
                    EnumSet.of(WorkflowStatus.ARCHIVED),
                    WorkflowStatus.ARCHIVED,
                    EnumSet.noneOf(WorkflowStatus.class));

    private static final Map<Status, Set<Status>> RECIPIENT =
            Map.of(
                    Status.PENDING,
                    EnumSet.of(Status.SENT, Status.EXPIRED),
                    Status.SENT,
                    EnumSet.of(Status.VIEWED, Status.SIGNED, Status.DECLINED, Status.EXPIRED),
                    Status.VIEWED,
                    EnumSet.of(Status.SIGNED, Status.DECLINED, Status.EXPIRED),
                    Status.SIGNED,
                    EnumSet.noneOf(Status.class),
                    Status.DECLINED,
                    EnumSet.noneOf(Status.class),
                    Status.EXPIRED,
                    EnumSet.noneOf(Status.class));

    @Test
    void everyWorkflowStatusHasAnExplicitTransitionSet() {
        for (WorkflowStatus from : WorkflowStatus.values()) {
            assertTrue(WORKFLOW.containsKey(from), "missing expectation for " + from);
            assertEquals(
                    WORKFLOW.get(from),
                    ESignatureWorkflowTransitions.allowedFrom(from),
                    "allowed transitions from " + from);
            for (WorkflowStatus to : WorkflowStatus.values()) {
                boolean expected = from == to || WORKFLOW.get(from).contains(to);
                assertEquals(
                        expected,
                        ESignatureWorkflowTransitions.canTransition(from, to),
                        from + " -> " + to);
                ESignatureWorkflow workflow = new ESignatureWorkflow();
                workflow.setStatus(from);
                if (expected) {
                    ESignatureWorkflowTransitions.transition(workflow, to);
                    assertEquals(to, workflow.getStatus());
                } else {
                    CodedResponseStatusException error =
                            assertThrows(
                                    CodedResponseStatusException.class,
                                    () -> ESignatureWorkflowTransitions.transition(workflow, to));
                    assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
                    assertEquals(ESignatureErrorCode.WORKFLOW_STATE_INVALID, error.getCode());
                    assertEquals(from.name(), error.getProperties().get("currentStatus"));
                    assertEquals(from, workflow.getStatus(), "state unchanged after rejection");
                }
            }
        }
    }

    @Test
    void everyRecipientStatusHasAnExplicitTransitionSet() {
        for (Status from : Status.values()) {
            assertTrue(RECIPIENT.containsKey(from), "missing expectation for " + from);
            assertEquals(
                    RECIPIENT.get(from),
                    ESignatureWorkflowTransitions.allowedFrom(from),
                    "allowed transitions from " + from);
            for (Status to : Status.values()) {
                boolean expected = from == to || RECIPIENT.get(from).contains(to);
                assertEquals(
                        expected,
                        ESignatureWorkflowTransitions.canTransition(from, to),
                        from + " -> " + to);
                SigningRecipient recipient = new SigningRecipient();
                recipient.setStatus(from);
                if (expected) {
                    ESignatureWorkflowTransitions.transition(recipient, to);
                    assertEquals(to, recipient.getStatus());
                } else {
                    CodedResponseStatusException error =
                            assertThrows(
                                    CodedResponseStatusException.class,
                                    () -> ESignatureWorkflowTransitions.transition(recipient, to));
                    assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
                    assertEquals(ESignatureErrorCode.RECIPIENT_STATE_INVALID, error.getCode());
                    assertEquals(from, recipient.getStatus(), "state unchanged after rejection");
                }
            }
        }
    }

    @Test
    void terminalStatesAcceptNoFurtherAction() {
        for (WorkflowStatus status : WorkflowStatus.values()) {
            assertEquals(
                    status == WorkflowStatus.DRAFT
                            || status == WorkflowStatus.SENT
                            || status == WorkflowStatus.IN_PROGRESS,
                    ESignatureWorkflowTransitions.isActive(status),
                    status.name());
        }
        for (Status status : Status.values()) {
            boolean terminal =
                    status == Status.SIGNED
                            || status == Status.DECLINED
                            || status == Status.EXPIRED;
            assertEquals(terminal, ESignatureWorkflowTransitions.isTerminal(status), status.name());
            if (terminal) {
                assertTrue(ESignatureWorkflowTransitions.allowedFrom(status).isEmpty());
            } else {
                assertFalse(ESignatureWorkflowTransitions.allowedFrom(status).isEmpty());
            }
        }
        assertTrue(ESignatureWorkflowTransitions.allowedFrom(WorkflowStatus.ARCHIVED).isEmpty());
    }
}
