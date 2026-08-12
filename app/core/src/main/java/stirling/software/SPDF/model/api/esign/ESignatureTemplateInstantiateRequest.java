package stirling.software.SPDF.model.api.esign;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureTemplateInstantiateRequest {
    private ESignatureCreateRequest overrides;
    private Boolean signingOrder;
    private Boolean remindersEnabled;
}
