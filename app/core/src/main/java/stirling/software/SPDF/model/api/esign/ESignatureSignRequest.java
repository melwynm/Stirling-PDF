package stirling.software.SPDF.model.api.esign;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
public class ESignatureSignRequest {
    private String signerName;
    private String signatureType;
    private String signatureDataUrl;
    private String accessCode;
    @ToString.Exclude private String otp;
    private boolean consentAccepted;
    private String consentText;
    private String ipAddress;
    private String userAgent;
    private Map<String, String> fieldValues = new LinkedHashMap<>();
    private Map<String, String> metadata = new LinkedHashMap<>();
}
