package stirling.software.SPDF.model.api.esign;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureTemplateCreateRequest {
    private String name;
    private String description;
    private ESignatureCreateRequest defaults = new ESignatureCreateRequest();
}
