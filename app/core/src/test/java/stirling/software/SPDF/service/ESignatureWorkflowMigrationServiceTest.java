package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.esign.ESignatureWorkflow;
import stirling.software.SPDF.model.signing.SigningRecipient;

class ESignatureWorkflowMigrationServiceTest {

    private final ESignatureWorkflowMigrationService service =
            new ESignatureWorkflowMigrationService();

    @Test
    void migratesLegacyPlaintextTokenAndNullCollections() {
        ESignatureWorkflow workflow = new ESignatureWorkflow();
        workflow.setModelVersion(0);
        SigningRecipient recipient = new SigningRecipient();
        recipient.setSigningToken("legacy-secret-token");
        recipient.setAuthentication(null);
        recipient.setMetadata(null);
        workflow.setRecipients(new java.util.ArrayList<>(java.util.List.of(recipient)));
        workflow.setFields(null);
        workflow.setAuditTrail(null);
        workflow.setWebhookDeliveries(null);

        ESignatureWorkflow migrated = service.migrate(workflow);

        assertEquals(ESignatureWorkflow.CURRENT_MODEL_VERSION, migrated.getModelVersion());
        assertNull(recipient.getSigningToken());
        assertNotNull(recipient.getSigningTokenHash());
        assertFalse(recipient.getSigningTokenHash().contains("legacy-secret-token"));
        assertNotNull(recipient.getAuthentication());
        assertNotNull(recipient.getMetadata());
        assertNotNull(migrated.getFields());
        assertNotNull(migrated.getAuditTrail());
        assertNotNull(migrated.getWebhookDeliveries());
    }

    @Test
    void rejectsUnsupportedFutureWorkflowVersion() {
        ESignatureWorkflow workflow = new ESignatureWorkflow();
        workflow.setModelVersion(ESignatureWorkflow.CURRENT_MODEL_VERSION + 1);

        ResponseStatusException error =
                assertThrows(ResponseStatusException.class, () -> service.migrate(workflow));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }
}
