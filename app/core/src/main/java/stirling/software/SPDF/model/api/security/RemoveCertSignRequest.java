package stirling.software.SPDF.model.api.security;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class RemoveCertSignRequest extends PDFFile {

    @Schema(
            description = "Signature handling mode",
            allowableValues = {"EXTRACT_REVISION", "CLEAR_UNSIGNED_FIELDS", "FLATTEN_SIGNATURES"},
            defaultValue = "EXTRACT_REVISION")
    private String mode = "EXTRACT_REVISION";

    @Schema(description = "One-based signature revision to extract; defaults to the latest")
    private Integer signatureIndex;

    @Schema(description = "Required acknowledgement for destructive signature flattening")
    private Boolean acknowledgeDestructive = false;
}
