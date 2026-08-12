package stirling.software.SPDF.model.signing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.util.StringUtils;

public final class SigningModelValidator {

    private SigningModelValidator() {}

    public static List<ValidationIssue> validate(
            List<SigningRecipient> recipients, List<SigningField> fields) {
        List<ValidationIssue> issues = new ArrayList<>();
        Set<String> recipientIds = new HashSet<>();

        if (recipients == null || recipients.isEmpty()) {
            issues.add(
                    new ValidationIssue(
                            "recipients", "required", "At least one recipient is required"));
        } else {
            for (int index = 0; index < recipients.size(); index++) {
                SigningRecipient recipient = recipients.get(index);
                String path = "recipients[" + index + "]";
                if (recipient == null) {
                    issues.add(new ValidationIssue(path, "required", "Recipient must not be null"));
                    continue;
                }
                if (!StringUtils.hasText(recipient.getId())) {
                    issues.add(
                            new ValidationIssue(
                                    path + ".id", "required", "Recipient id is required"));
                } else if (!recipientIds.add(recipient.getId())) {
                    issues.add(
                            new ValidationIssue(
                                    path + ".id", "duplicate", "Recipient id must be unique"));
                }
                if (!StringUtils.hasText(recipient.getName())) {
                    issues.add(
                            new ValidationIssue(
                                    path + ".name", "required", "Recipient name is required"));
                }
                if (!StringUtils.hasText(recipient.getEmail())) {
                    issues.add(
                            new ValidationIssue(
                                    path + ".email", "required", "Recipient email is required"));
                }
                if (recipient.getRole() == null) {
                    issues.add(
                            new ValidationIssue(
                                    path + ".role", "required", "Recipient role is required"));
                }
                if (recipient.getAuthentication() == null
                        || recipient.getAuthentication().getMethod() == null
                        || !recipient.getAuthentication().isConfigured()) {
                    issues.add(
                            new ValidationIssue(
                                    path + ".authentication",
                                    "required",
                                    "Recipient authentication must be configured"));
                }
                if (recipient.getSigningOrder() < 1) {
                    issues.add(
                            new ValidationIssue(
                                    path + ".signingOrder",
                                    "invalid",
                                    "Signing order must be at least 1"));
                }
            }
        }

        Set<String> fieldIds = new HashSet<>();
        Set<String> fieldNames = new HashSet<>();
        if (fields == null) {
            issues.add(new ValidationIssue("fields", "required", "Fields must not be null"));
            return issues;
        }
        for (int index = 0; index < fields.size(); index++) {
            SigningField field = fields.get(index);
            String path = "fields[" + index + "]";
            if (field == null) {
                issues.add(new ValidationIssue(path, "required", "Field must not be null"));
                continue;
            }
            if (!StringUtils.hasText(field.getId())) {
                issues.add(new ValidationIssue(path + ".id", "required", "Field id is required"));
            } else if (!fieldIds.add(field.getId())) {
                issues.add(
                        new ValidationIssue(path + ".id", "duplicate", "Field id must be unique"));
            }
            if (!StringUtils.hasText(field.getName())) {
                issues.add(
                        new ValidationIssue(path + ".name", "required", "Field name is required"));
            } else if (!fieldNames.add(field.getName())) {
                issues.add(
                        new ValidationIssue(
                                path + ".name", "duplicate", "Field name must be unique"));
            }
            if (!StringUtils.hasText(field.getRecipientId())) {
                issues.add(
                        new ValidationIssue(
                                path + ".recipientId",
                                "required",
                                "Field recipient id is required"));
            } else if (!recipientIds.contains(field.getRecipientId())) {
                issues.add(
                        new ValidationIssue(
                                path + ".recipientId",
                                "unknown_recipient",
                                "Field recipient does not exist"));
            }
            if (field.getType() == null) {
                issues.add(
                        new ValidationIssue(path + ".type", "required", "Field type is required"));
            }
            if (field.getPageIndex() < 0) {
                issues.add(
                        new ValidationIssue(
                                path + ".pageIndex",
                                "invalid_page",
                                "Field page index must be zero or greater"));
            }
            if (field.getBounds() == null || !field.getBounds().isWithinPage()) {
                issues.add(
                        new ValidationIssue(
                                path + ".bounds",
                                "invalid_bounds",
                                "Field bounds must fit within the normalized page"));
            }
        }
        return issues;
    }

    public record ValidationIssue(String path, String code, String message) {}
}
