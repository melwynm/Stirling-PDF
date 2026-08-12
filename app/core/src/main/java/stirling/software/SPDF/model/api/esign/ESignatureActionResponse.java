package stirling.software.SPDF.model.api.esign;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureActionResponse {
    private ESignatureRequestView request;
    private List<ESignatureNotification> notifications = new ArrayList<>();
}
