package stirling.software.SPDF.model.api.esign;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureNotification {
    private String requestId;
    private String recipientId;
    private String recipientName;
    private String recipientEmail;
    private String recipientPhone;
    private String subject;
    private String message;
    private String signingUrl;
    private String documentUrl;
    private String deliveryChannel = "email";
    private String deliveryStatus;
    private int deliveryAttemptCount;
    private String deliveryError;
    private String token;
    private String eventType;
}
