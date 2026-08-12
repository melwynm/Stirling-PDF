package stirling.software.SPDF.model.api.esign;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureReminderRequest {
    private String publicBaseUrl;
    private String message;
    private boolean onlyDue;
    private List<String> recipientIds = new ArrayList<>();
}
