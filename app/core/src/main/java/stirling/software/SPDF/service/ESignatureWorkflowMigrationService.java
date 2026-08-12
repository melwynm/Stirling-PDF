package stirling.software.SPDF.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.esign.ESignatureWorkflow;
import stirling.software.SPDF.model.signing.SigningRecipient;
import stirling.software.SPDF.model.signing.SigningRecipient.Authentication;

@Service
public class ESignatureWorkflowMigrationService {

    public ESignatureWorkflow migrate(ESignatureWorkflow workflow) {
        if (workflow.getModelVersion() > ESignatureWorkflow.CURRENT_MODEL_VERSION) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "E-signature workflow model version "
                            + workflow.getModelVersion()
                            + " is newer than supported version "
                            + ESignatureWorkflow.CURRENT_MODEL_VERSION);
        }
        normalizeCollections(workflow);
        if (workflow.getModelVersion() < 1) {
            migrateLegacyTokens(workflow);
            workflow.setModelVersion(1);
        }
        return workflow;
    }

    private void normalizeCollections(ESignatureWorkflow workflow) {
        if (workflow.getRecipients() == null) workflow.setRecipients(new ArrayList<>());
        for (SigningRecipient recipient : workflow.getRecipients()) {
            if (recipient.getAuthentication() == null)
                recipient.setAuthentication(new Authentication());
            if (recipient.getMetadata() == null)
                recipient.setMetadata(new java.util.LinkedHashMap<>());
        }
        if (workflow.getFields() == null) workflow.setFields(new ArrayList<>());
        if (workflow.getAuditTrail() == null) workflow.setAuditTrail(new ArrayList<>());
        if (workflow.getWebhookDeliveries() == null)
            workflow.setWebhookDeliveries(new ArrayList<>());
    }

    private void migrateLegacyTokens(ESignatureWorkflow workflow) {
        for (SigningRecipient recipient : workflow.getRecipients()) {
            if (StringUtils.hasText(recipient.getSigningToken())
                    && !StringUtils.hasText(recipient.getSigningTokenHash())) {
                recipient.setSigningTokenHash(sha256(recipient.getSigningToken()));
            }
            recipient.setSigningToken(null);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
