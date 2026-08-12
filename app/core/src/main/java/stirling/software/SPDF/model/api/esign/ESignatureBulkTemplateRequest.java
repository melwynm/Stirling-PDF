package stirling.software.SPDF.model.api.esign;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureBulkTemplateRequest {
    private boolean sendImmediately;
    private String publicBaseUrl;
    private List<ESignatureTemplateInstantiateRequest> items = new ArrayList<>();
}
