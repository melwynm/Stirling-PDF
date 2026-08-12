package stirling.software.SPDF.model.api.esign;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureDeclineRequest {
    private String reason;
    private String accessCode;
    private String ipAddress;
    private String userAgent;
}
