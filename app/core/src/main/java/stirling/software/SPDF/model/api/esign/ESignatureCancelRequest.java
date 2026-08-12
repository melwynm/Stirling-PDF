package stirling.software.SPDF.model.api.esign;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureCancelRequest {
    private String reason;
}
