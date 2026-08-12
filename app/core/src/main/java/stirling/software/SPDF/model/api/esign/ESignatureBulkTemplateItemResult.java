package stirling.software.SPDF.model.api.esign;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureBulkTemplateItemResult {
    private int index;
    private boolean success;
    private ESignatureActionResponse action;
    private ESignatureRequestView request;
    private String error;
}
