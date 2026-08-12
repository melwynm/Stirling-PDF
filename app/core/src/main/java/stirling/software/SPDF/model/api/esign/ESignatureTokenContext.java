package stirling.software.SPDF.model.api.esign;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureTokenContext {
    private ESignatureRequestView request;
    private ESignatureRecipientView recipient;
    private String downloadUrl;
}
