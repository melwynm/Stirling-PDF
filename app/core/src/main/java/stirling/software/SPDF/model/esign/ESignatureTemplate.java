package stirling.software.SPDF.model.esign;

import java.time.Instant;

import lombok.Data;
import lombok.NoArgsConstructor;

import stirling.software.SPDF.model.api.esign.ESignatureCreateRequest;

@Data
@NoArgsConstructor
public class ESignatureTemplate {
    private String id;
    private String ownerId;
    private String name;
    private String description;
    private String originalFilename;
    private String documentFileName = "document.pdf";
    private Instant createdAt;
    private Instant updatedAt;
    private ESignatureCreateRequest defaults = new ESignatureCreateRequest();
}
