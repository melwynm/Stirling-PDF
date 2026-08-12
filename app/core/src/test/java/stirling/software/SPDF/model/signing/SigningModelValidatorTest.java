package stirling.software.SPDF.model.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.SPDF.model.signing.SigningField.NormalizedBounds;
import stirling.software.SPDF.model.signing.SigningModelValidator.ValidationIssue;

import tools.jackson.databind.json.JsonMapper;

class SigningModelValidatorTest {

    @Test
    void acceptsRecipientAwareNormalizedFields() {
        List<ValidationIssue> issues =
                SigningModelValidator.validate(
                        List.of(recipient("recipient-1")),
                        List.of(field("field-1", "signature-1", "recipient-1")));

        assertTrue(issues.isEmpty());
    }

    @Test
    void reportsDuplicateFieldsUnknownRecipientsAndInvalidBounds() {
        SigningField first = field("field-1", "signature-1", "missing", 0.9, 0.2, 0.2, 0.1);
        SigningField duplicate = field("field-1", "signature-1", "recipient-1");

        List<ValidationIssue> issues =
                SigningModelValidator.validate(
                        List.of(recipient("recipient-1")), List.of(first, duplicate));

        assertTrue(
                issues.stream()
                        .anyMatch(
                                issue ->
                                        issue.path().equals("fields[0].recipientId")
                                                && issue.code().equals("unknown_recipient")));
        assertTrue(
                issues.stream()
                        .anyMatch(
                                issue ->
                                        issue.path().equals("fields[0].bounds")
                                                && issue.code().equals("invalid_bounds")));
        assertTrue(
                issues.stream()
                        .anyMatch(
                                issue ->
                                        issue.path().equals("fields[1].id")
                                                && issue.code().equals("duplicate")));
        assertTrue(
                issues.stream()
                        .anyMatch(
                                issue ->
                                        issue.path().equals("fields[1].name")
                                                && issue.code().equals("duplicate")));
    }

    @Test
    void serializesSharedEnumsUsingFrontendContractValues() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();

        assertEquals("\"approver\"", mapper.writeValueAsString(SigningRecipient.Role.APPROVER));
        assertEquals("\"dateSigned\"", mapper.writeValueAsString(SigningField.Type.DATE_SIGNED));
        assertEquals(
                SigningField.Type.DATE_SIGNED,
                mapper.readValue("\"dateSigned\"", SigningField.Type.class));
    }

    private SigningRecipient recipient(String id) {
        SigningRecipient recipient = new SigningRecipient();
        recipient.setId(id);
        recipient.setName("Signer");
        recipient.setEmail("signer@example.com");
        recipient.setSigningOrder(1);
        return recipient;
    }

    private SigningField field(String id, String name, String recipientId) {
        return field(id, name, recipientId, 0.5, 0.7, 0.35, 0.12);
    }

    private SigningField field(
            String id,
            String name,
            String recipientId,
            double x,
            double y,
            double width,
            double height) {
        NormalizedBounds bounds = new NormalizedBounds();
        bounds.setX(x);
        bounds.setY(y);
        bounds.setWidth(width);
        bounds.setHeight(height);

        SigningField field = new SigningField();
        field.setId(id);
        field.setName(name);
        field.setLabel(name);
        field.setRecipientId(recipientId);
        field.setPageIndex(0);
        field.setBounds(bounds);
        return field;
    }
}
