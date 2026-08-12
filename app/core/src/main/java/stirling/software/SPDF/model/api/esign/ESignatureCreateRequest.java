package stirling.software.SPDF.model.api.esign;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

import stirling.software.SPDF.model.signing.SigningField;

@Data
@NoArgsConstructor
public class ESignatureCreateRequest {
    private String title;
    private String message;
    private String requesterName;
    private String requesterEmail;
    private String publicBaseUrl;
    private String callbackUrl;
    private Instant expiresAt;
    private boolean signingOrder;
    private boolean remindersEnabled = true;
    private Integer reminderIntervalHours;
    private List<ESignatureRecipientRequest> recipients = new ArrayList<>();
    private List<SigningField> fields = new ArrayList<>();
}
