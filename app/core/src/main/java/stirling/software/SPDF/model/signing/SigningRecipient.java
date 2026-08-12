package stirling.software.SPDF.model.signing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SigningRecipient {

    private String id;
    private String name;
    private String email;
    private Role role = Role.SIGNER;
    private int signingOrder = 1;
    private Authentication authentication = new Authentication();
    private Status status = Status.PENDING;

    /** Legacy plaintext token, read only for migration of existing workflow metadata. */
    private String signingToken;

    private String signingTokenHash;
    private Instant createdAt;
    private Instant sentAt;
    private Instant viewedAt;
    private Instant signedAt;
    private Instant declinedAt;
    private Instant lastReminderAt;
    private int reminderCount;
    private String signatureType;
    private String signatureDataUrl;
    private String signerName;
    private String consentText;
    private String declineReason;
    private Map<String, String> metadata = new LinkedHashMap<>();

    public enum Role {
        SIGNER("signer"),
        APPROVER("approver"),
        CC("cc");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static Role fromValue(String value) {
            if (value == null || value.isBlank()) {
                return SIGNER;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Role role : values()) {
                if (role.value.equals(normalized)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("Unknown signing recipient role: " + value);
        }
    }

    public enum Status {
        PENDING,
        SENT,
        VIEWED,
        SIGNED,
        DECLINED,
        EXPIRED
    }

    @Data
    @NoArgsConstructor
    public static class Authentication {
        private Method method = Method.EMAIL_LINK;
        private String accessCodeHash;
        private int failedAttempts;
        private Instant lockedUntil;

        public boolean isConfigured() {
            return method == Method.EMAIL_LINK
                    || (method == Method.ACCESS_CODE
                            && accessCodeHash != null
                            && !accessCodeHash.isBlank());
        }
    }

    public enum Method {
        EMAIL_LINK("emailLink"),
        ACCESS_CODE("accessCode");

        private final String value;

        Method(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static Method fromValue(String value) {
            if (value == null || value.isBlank()) {
                return EMAIL_LINK;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Method method : values()) {
                if (method.value.toLowerCase(Locale.ROOT).equals(normalized)) {
                    return method;
                }
            }
            throw new IllegalArgumentException("Unknown signing authentication method: " + value);
        }
    }
}
