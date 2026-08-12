package stirling.software.SPDF.model.signing;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SigningAnchor {
    private String text;
    private String recipientId;
    private String name;
    private String label;
    private SigningField.Type type = SigningField.Type.SIGNATURE;
    private boolean required = true;
    private boolean matchCase;
    private boolean allOccurrences;
    private double offsetX;
    private double offsetY;
    private double width = 0.25;
    private double height = 0.08;
}
