package stirling.software.SPDF.model.api.esign;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureSignRequest {
    private String signerName;
    private String signatureType;
    private String signatureDataUrl;
    private String accessCode;
    private boolean consentAccepted;
    private String consentText;
    private String ipAddress;
    private String userAgent;
    private Map<String, String> fieldValues = new LinkedHashMap<>();
    private Map<String, String> metadata = new LinkedHashMap<>();
}
