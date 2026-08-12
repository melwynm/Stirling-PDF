package stirling.software.SPDF.model.api.esign;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureSendRequest {
    private String publicBaseUrl;
    private String message;
    private boolean rotateTokens;
    private List<String> recipientIds = new ArrayList<>();
}
