package stirling.software.SPDF.model.api.security;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class SignPDFWithCertRequest extends PDFFile {

    @Schema(
            description = "The type of the digital certificate",
            allowableValues = {"PEM", "PKCS12", "PFX", "JKS", "SERVER", "KMS"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String certType;

    @Schema(
            description =
                    "The private key for the digital certificate (required for PEM type"
                            + " certificates, supports .pem, .der, or .key files)")
    private MultipartFile privateKeyFile;

    @Schema(
            description =
                    "The digital certificate (required for PEM type certificates, supports"
                            + " .pem, .der, .crt, or .cer files)")
    private MultipartFile certFile;

    @Schema(
            description =
                    "The PKCS12/PFX keystore file (required for PKCS12 or PFX type certificates)")
    private MultipartFile p12File;

    @Schema(description = "The JKS keystore file (Java Key Store)")
    private MultipartFile jksFile;

    @Schema(description = "The password for the keystore or the private key", format = "password")
    private String password;

    @Schema(description = "KMS key identifier to pass to the configured KMS signing bridge")
    private String kmsKeyId;

    @Schema(
            description = "Managed signer provider",
            allowableValues = {"KMS", "REMOTE", "CLOUD_KMS", "QES", "PKCS11"},
            defaultValue = "KMS")
    private String signerProvider = "KMS";

    @Schema(
            description = "KMS signature algorithm",
            allowableValues = {"SHA256_WITH_RSA", "SHA256_WITH_ECDSA"},
            defaultValue = "SHA256_WITH_RSA")
    private String kmsSignatureAlgorithm;

    @Schema(
            description = "Whether to visually show the signature in the PDF file",
            defaultValue = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean showSignature;

    @Schema(description = "The reason for signing the PDF", defaultValue = "Signed by SPDF")
    private String reason;

    @Schema(description = "The location where the PDF is signed", defaultValue = "SPDF")
    private String location;

    @Schema(description = "The name of the signer", defaultValue = "SPDF")
    private String name;

    @Schema(
            description =
                    "The page number where the signature should be visible. This is required if"
                            + " showSignature is set to true",
            defaultValue = "1")
    private Integer pageNumber;

    @Schema(
            description = "Whether to visually show a signature logo along with the signature",
            defaultValue = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean showLogo;

    @Schema(description = "Optional custom PNG or JPEG for the visible signature appearance")
    private MultipartFile signatureImage;

    @Schema(description = "Optional custom text shown in the visible signature appearance")
    private String signatureText;

    @Schema(description = "Name of an existing unsigned PDF signature field to target")
    private String signatureFieldName;

    @Schema(
            description = "PAdES baseline profile",
            allowableValues = {"B_B", "B_T", "B_LT", "B_LTA"},
            defaultValue = "B_B")
    private String padesProfile = "B_B";

    @Schema(description = "RFC 3161 timestamp authority URL, required for B-T and above")
    private String tsaUrl;
}
