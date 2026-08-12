package stirling.software.SPDF.model.api.esign;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

import stirling.software.SPDF.model.signing.SigningRecipient.DeliveryChannel;
import stirling.software.SPDF.model.signing.SigningRecipient.Method;
import stirling.software.SPDF.model.signing.SigningRecipient.Role;
import stirling.software.SPDF.model.signing.SigningRecipient.Status;

@Data
@NoArgsConstructor
public class ESignatureRecipientView {
    private String id;
    private String name;
    private String email;
    private String phoneNumber;
    private DeliveryChannel deliveryChannel;
    private Role role;
    private int signingOrder;
    private Method authenticationMethod;
    private boolean authenticationConfigured;
    private Status status;
    private Instant sentAt;
    private Instant viewedAt;
    private Instant signedAt;
    private Instant declinedAt;
    private Instant lastReminderAt;
    private int reminderCount;
    private String signerName;
    private String signatureType;
    private String declineReason;
    private Map<String, String> metadata = new LinkedHashMap<>();
}
