package stirling.software.SPDF.model.api.esign;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ESignatureBulkTemplateResponse {
    private int requested;
    private int succeeded;
    private int failed;
    private List<ESignatureBulkTemplateItemResult> results = new ArrayList<>();
}
