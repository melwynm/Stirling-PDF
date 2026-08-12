package stirling.software.SPDF.model.api.esign;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

import stirling.software.SPDF.model.signing.SigningRecipient.Method;
import stirling.software.SPDF.model.signing.SigningRecipient.Role;

@Data
@NoArgsConstructor
public class ESignatureRecipientRequest {
    private String id;
    private String name;
    private String email;
    private Role role = Role.SIGNER;
    private Integer signingOrder;
    private Method authenticationMethod = Method.EMAIL_LINK;
    private String accessCode;
    private Map<String, String> metadata = new LinkedHashMap<>();
}
